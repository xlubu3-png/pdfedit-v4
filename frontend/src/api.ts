import type {
  AddedText,
  AppInfo,
  FindMatch,
  FontChoice,
  PageItem,
  PageText,
  ReplaceResult,
  UploadResponse,
} from './types'

const BASE = '/api/v1/pdf'

/** A failed request; `status` tells a refused edit (4xx: change it) from a server that cannot be reached (retry). */
export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function failure(response: Response, fallback: string): Promise<ApiError> {
  const detail = await response.text().catch(() => '')
  return new ApiError(detail ? `${fallback}: ${detail}` : `${fallback} (${response.status})`, response.status)
}

export async function uploadDocument(file: File): Promise<UploadResponse> {
  const formData = new FormData()
  formData.append('file', file)

  const response = await fetch(`${BASE}/documents`, {
    method: 'POST',
    body: formData,
  })
  if (!response.ok) {
    throw new ApiError(`업로드 실패: ${file.name} (${response.status})`, response.status)
  }
  return response.json()
}

/** The name and page count of a document the server still holds, or null when it is gone. */
export async function fetchDocumentInfo(documentId: string): Promise<UploadResponse | null> {
  const response = await fetch(`${BASE}/documents/${documentId}`)
  if (response.status === 404) return null
  if (!response.ok) {
    throw await failure(response, '문서 정보를 불러오지 못했습니다')
  }
  return response.json()
}

/** Has the server delete its stored copy of a document; failures are ignored (the copy expires by itself). */
export async function deleteDocument(documentId: string): Promise<void> {
  try {
    await fetch(`${BASE}/documents/${documentId}`, { method: 'DELETE' })
  } catch {
    // offline or the server is gone: nothing more to do
  }
}

export function thumbnailUrl(documentId: string, pageIndex: number, width = 240, revision = 0): string {
  return `${BASE}/documents/${documentId}/pages/${pageIndex}/thumbnail?width=${width}&rev=${revision}`
}

export function previewUrl(documentId: string, pageIndex: number, revision: number, dpi = 150): string {
  return `${BASE}/documents/${documentId}/pages/${pageIndex}/preview?rev=${revision}&dpi=${dpi}`
}

export async function fetchAppInfo(): Promise<AppInfo> {
  const response = await fetch(`${BASE}/info`)
  if (!response.ok) {
    throw await failure(response, '앱 정보를 불러오지 못했습니다')
  }
  return response.json()
}

export async function createBlankDocument(): Promise<UploadResponse> {
  const response = await fetch(`${BASE}/documents/blank`, { method: 'POST' })
  if (!response.ok) {
    throw new ApiError(`빈 페이지 생성 실패 (${response.status})`, response.status)
  }
  return response.json()
}

export async function fetchPageText(documentId: string, pageIndex: number): Promise<PageText> {
  const response = await fetch(`${BASE}/documents/${documentId}/pages/${pageIndex}/text-runs`)
  if (!response.ok) {
    throw await failure(response, '텍스트를 불러오지 못했습니다')
  }
  return response.json()
}

let fontsRequest: Promise<FontChoice[]> | null = null

/** The font families installed on this computer; fetched once. */
export function fetchFonts(): Promise<FontChoice[]> {
  fontsRequest ??= fetch(`${BASE}/fonts`)
    .then((response) => {
      if (!response.ok) throw new Error(`글꼴 목록을 불러오지 못했습니다 (${response.status})`)
      return response.json() as Promise<FontChoice[]>
    })
    .catch((e) => {
      fontsRequest = null
      throw e
    })
  return fontsRequest
}

export interface TextEditPayload {
  index: number
  text: string
  fontFamily: string | null
  fontSize: number | null
  bold: boolean | null
  color: string | null
  dx: number
  dy: number
}

/** Replaces the text boxes added to a page with this list. */
export async function saveAddedTexts(documentId: string, pageIndex: number, boxes: AddedText[]): Promise<void> {
  const response = await fetch(`${BASE}/documents/${documentId}/pages/${pageIndex}/added-texts`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(boxes),
  })
  if (!response.ok) {
    throw await failure(response, '텍스트 상자를 저장하지 못했습니다')
  }
}

export async function saveTextEdits(
  documentId: string,
  pageIndex: number,
  items: TextEditPayload[],
): Promise<void> {
  const response = await fetch(`${BASE}/documents/${documentId}/pages/${pageIndex}/text-edits`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(items),
  })
  if (!response.ok) {
    throw await failure(response, '저장하지 못했습니다')
  }
}

export async function resetTextEdits(documentId: string, pageIndex: number): Promise<void> {
  const response = await fetch(`${BASE}/documents/${documentId}/pages/${pageIndex}/text-edits`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    throw await failure(response, '되돌리지 못했습니다')
  }
}

/** The text lines of `pages` that contain `query`. */
export async function findText(pages: PageItem[], query: string, matchCase: boolean): Promise<FindMatch[]> {
  const response = await fetch(`${BASE}/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pages: pageRefs(pages), query, matchCase }),
  })
  if (!response.ok) {
    throw await failure(response, '찾지 못했습니다')
  }
  return response.json()
}

export async function replaceText(
  pages: PageItem[],
  find: string,
  replace: string,
  matchCase: boolean,
): Promise<ReplaceResult> {
  const response = await fetch(`${BASE}/replace`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pages: pageRefs(pages), find, replace, matchCase }),
  })
  if (!response.ok) {
    throw await failure(response, '바꾸지 못했습니다')
  }
  return response.json()
}

function pageRefs(pages: PageItem[]) {
  return pages.map((p) => ({ documentId: p.documentId, pageIndex: p.pageIndex, rotation: 0 }))
}

export async function exportPdf(pages: PageItem[], fileName: string, flatten = false): Promise<Blob> {
  const response = await fetch(`${BASE}/export`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      pages: pages.map((p) => ({
        documentId: p.documentId,
        pageIndex: p.pageIndex,
        rotation: p.rotation,
      })),
      fileName,
      flatten,
    }),
  })
  if (!response.ok) {
    throw await failure(response, '내보내기 실패')
  }
  return response.blob()
}
