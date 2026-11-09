import type {
  AddedImage,
  AddedText,
  AppInfo,
  AppSettings,
  CompareResult,
  FindMatch,
  FontChoice,
  OcrResult,
  OcrText,
  PageItem,
  PageText,
  PersonalInfoKind,
  PersonalInfoMatch,
  Redaction,
  ReplaceResult,
  Span,
  UpdateInfo,
  UploadResponse,
  VisualResult,
} from './types'
import { isKorean } from './errors'
import { parseRedactionReport, type RedactionReport } from './redaction'
import { parseShrinkReport, type ShrinkReport } from './shrink'
import type { Grid, Rulings } from './tableGrid'

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

/** The status of a failed request, said in Korean, for an answer that carries no message of the app's own. */
function statusText(status: number): string {
  if (status === 404) return '찾을 수 없습니다. 보관 기간이 지나 지워졌을 수 있으니 파일을 다시 열어 주세요.'
  if (status === 413) return '파일이 너무 큽니다.'
  if (status >= 500) return '앱에서 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  return `오류 ${status}`
}

/**
 * The error for a refused request. The server's own messages (in Korean) are shown as they are; anything else it
 * answers - the web server's English error pages ("Internal Server Error", "Payload Too Large") - is not.
 */
export async function failure(response: Response, fallback: string): Promise<ApiError> {
  const detail = (await response.text().catch(() => '')).trim()
  return new ApiError(`${fallback}: ${isKorean(detail) ? detail : statusText(response.status)}`, response.status)
}

/** Said when the app does not answer at all: it was closed, or is still starting. */
export const UNREACHABLE = '앱에 연결하지 못했습니다. 앱이 실행 중인지 확인하고 다시 시도해 주세요.'

/**
 * `fetch`, but a request that never reaches the app fails with a Korean ApiError (status 0) instead of the
 * browser's "Failed to fetch". Status 0 is no 4xx, so an editor keeps the edits to send again.
 */
async function call(input: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(input, init)
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(UNREACHABLE, 0)
  }
}

/** The biggest file the app takes (spring.servlet.multipart.max-file-size). */
export const MAX_UPLOAD_MB = 100

/** An encrypted PDF without its password, or with a wrong one, fails with status 401. */
export async function uploadDocument(file: File, password?: string): Promise<UploadResponse> {
  if (file.size > MAX_UPLOAD_MB * 1024 * 1024) {
    // Refused before sending: the server would drop the connection halfway through such a file.
    throw new ApiError(`업로드 실패: ${file.name}: 파일이 너무 큽니다 (한 파일에 ${MAX_UPLOAD_MB}MB까지 열 수 있습니다)`, 413)
  }
  const formData = new FormData()
  formData.append('file', file)
  if (password !== undefined) formData.append('password', password)

  const response = await call(`${BASE}/documents`, {
    method: 'POST',
    body: formData,
  })
  if (!response.ok) {
    throw await failure(response, `업로드 실패: ${file.name}`)
  }
  return response.json()
}

/** The name and page count of a document the server still holds, or null when it is gone. */
export async function fetchDocumentInfo(documentId: string): Promise<UploadResponse | null> {
  const response = await call(`${BASE}/documents/${documentId}`)
  if (response.status === 404) return null
  if (!response.ok) {
    throw await failure(response, '문서 정보를 불러오지 못했습니다')
  }
  return response.json()
}

/** Has the server delete its stored copy of a document; failures are ignored (the copy expires by itself). */
export async function deleteDocument(documentId: string): Promise<void> {
  try {
    await call(`${BASE}/documents/${documentId}`, { method: 'DELETE' })
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

export async function fetchSettings(): Promise<AppSettings> {
  const response = await call(`${BASE}/settings`)
  if (!response.ok) {
    throw await failure(response, '설정을 불러오지 못했습니다')
  }
  return response.json()
}

export async function saveSettings(settings: AppSettings): Promise<AppSettings> {
  const response = await call(`${BASE}/settings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
  if (!response.ok) {
    throw await failure(response, '설정을 저장하지 못했습니다')
  }
  return response.json()
}

export async function fetchAppInfo(): Promise<AppInfo> {
  const response = await call(`${BASE}/info`)
  if (!response.ok) {
    throw await failure(response, '앱 정보를 불러오지 못했습니다')
  }
  return response.json()
}

/** Whether a newer version is published. `refresh` asks GitHub again instead of using an answer from hours ago. */
export async function fetchUpdate(refresh = false): Promise<UpdateInfo> {
  const response = await call(`${BASE}/update?refresh=${refresh}`)
  if (!response.ok) {
    throw await failure(response, '업데이트를 확인하지 못했습니다')
  }
  return response.json()
}

/**
 * Has the app download the newest version and start its installer; the app quits a moment after
 * answering. The marker header is what tells the server that the request comes from this page.
 */
export async function installUpdate(): Promise<string> {
  const response = await call(`${BASE}/update/install`, {
    method: 'POST',
    headers: { 'X-Requested-With': 'WINTECH_PDF' },
  })
  if (!response.ok) {
    throw await failure(response, '업데이트하지 못했습니다')
  }
  return response.text()
}

/** Has the server read the font folders again, for a font installed while the app runs; the new font count. */
export async function reloadFonts(): Promise<AppInfo> {
  const response = await call(`${BASE}/fonts/reload`, { method: 'POST' })
  if (!response.ok) {
    throw await failure(response, '글꼴을 다시 읽지 못했습니다')
  }
  fontsRequest = null // the list of families is read again too
  return response.json()
}

export async function createBlankDocument(): Promise<UploadResponse> {
  const response = await call(`${BASE}/documents/blank`, { method: 'POST' })
  if (!response.ok) {
    throw new ApiError(`빈 페이지 생성 실패 (${response.status})`, response.status)
  }
  return response.json()
}

/** Recognizes the text of a scanned page (a second or two); the server keeps it for the export. */
export async function recognizePage(documentId: string, pageIndex: number): Promise<OcrResult> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/ocr`, { method: 'POST' })
  if (!response.ok) {
    throw await failure(response, '글자를 인식하지 못했습니다')
  }
  return response.json()
}

/** The text recognized on the page earlier (no lines when it never was). */
export async function fetchPageOcr(documentId: string, pageIndex: number): Promise<OcrResult> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/ocr`)
  if (!response.ok) {
    throw await failure(response, '인식한 글자를 불러오지 못했습니다')
  }
  return response.json()
}

/**
 * A text box that rewrites recognized line `line` of the page: its letters, sized and coloured like the scan's,
 * over a patch that hides the scanned ones. Nothing is saved until the box is.
 */
export async function ocrTextBox(documentId: string, pageIndex: number, line: number): Promise<Omit<AddedText, 'id'>> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/ocr/${line}/text-box`, {
    method: 'POST',
  })
  if (!response.ok) {
    throw await failure(response, '인식한 글자를 고쳐 쓸 수 있게 바꾸지 못했습니다')
  }
  return response.json()
}

/**
 * Corrects what recognition read off recognized line `line`: the scan stays as it is, the corrected text is what
 * search, copying and the export say. Gives back the line as it is now.
 */
export async function correctOcrLine(documentId: string, pageIndex: number, line: number, text: string): Promise<OcrText> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/ocr/${line}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  })
  if (!response.ok) {
    throw await failure(response, '인식한 글자를 고치지 못했습니다')
  }
  return response.json()
}

/** A copy of the page, with everything done to it, as a one-page document of its own. */
/** A scanned page set straight: its straightened copy (one page), or null when it was straight already or no scan. */
export async function straightenPage(
  documentId: string,
  pageIndex: number,
  shown = 0,
): Promise<{ documentId: string | null; degrees: number; quarter: number }> {
  // How the list shows the page turned: turned by the user, its way up is theirs (only its slant is set straight).
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/straighten?shown=${shown}`, {
    method: 'POST',
  })
  if (!response.ok) {
    throw await failure(response, '쪽의 기울기를 바로잡지 못했습니다')
  }
  return response.json()
}

/** The tables set out by hand on the page (표 칸 바로잡기); empty when the page's own are used. */
export async function fetchTableGrids(documentId: string, pageIndex: number): Promise<Grid[]> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/table-grids`)
  if (!response.ok) {
    throw await failure(response, '표 칸을 불러오지 못했습니다')
  }
  return response.json()
}

export async function saveTableGrids(documentId: string, pageIndex: number, grids: Grid[]): Promise<void> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/table-grids`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(grids),
  })
  if (!response.ok) {
    throw await failure(response, '표 칸을 저장하지 못했습니다')
  }
}

/** 문제 보고: the app's logs and a page about this PC, as a ZIP to send (no document is in it). */
export async function fetchProblemReport(): Promise<{ blob: Blob; fileName: string }> {
  const response = await call(`${BASE}/support/report`)
  if (!response.ok) {
    throw await failure(response, '문제 보고 파일을 만들지 못했습니다')
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const named = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  return { blob: await response.blob(), fileName: named ? decodeURIComponent(named[1]) : 'WINTECH_문제보고.zip' }
}

/** How big the page is (points): fast, for its picture to be shown before its lines are read. */
export async function fetchPageSize(documentId: string, pageIndex: number): Promise<{ width: number; height: number }> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/size`)
  if (!response.ok) {
    throw await failure(response, '페이지를 불러오지 못했습니다')
  }
  return response.json()
}

/** A stamp (도장·서명): its size when stamped (points) and where it went last (shares of the page, top-left). */
export interface Stamp {
  id: string
  name: string
  width: number
  height: number
  lastX: number | null
  lastY: number | null
}

export async function fetchStamps(): Promise<Stamp[]> {
  const response = await call(`${BASE}/stamps`)
  if (!response.ok) throw await failure(response, '도장 목록을 불러오지 못했습니다')
  return response.json()
}

export async function addStamp(file: File, name: string): Promise<Stamp> {
  const form = new FormData()
  form.append('file', file)
  form.append('name', name)
  const response = await call(`${BASE}/stamps`, { method: 'POST', body: form })
  if (!response.ok) throw await failure(response, '도장을 등록하지 못했습니다')
  return response.json()
}

export function stampImageUrl(id: string): string {
  return `${BASE}/stamps/${id}/image`
}

export async function updateStamp(
  id: string,
  change: { name?: string; width?: number; height?: number; lastX?: number; lastY?: number },
): Promise<Stamp> {
  const response = await call(`${BASE}/stamps/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(change),
  })
  if (!response.ok) throw await failure(response, '도장을 바꾸지 못했습니다')
  return response.json()
}

export async function deleteStamp(id: string): Promise<void> {
  const response = await call(`${BASE}/stamps/${id}`, { method: 'DELETE' })
  if (!response.ok) throw await failure(response, '도장을 지우지 못했습니다')
}

export async function duplicatePage(documentId: string, pageIndex: number): Promise<UploadResponse> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/duplicate`, { method: 'POST' })
  if (!response.ok) {
    throw await failure(response, '페이지를 복제하지 못했습니다')
  }
  return response.json()
}

export async function fetchPageText(documentId: string, pageIndex: number): Promise<PageText> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/text-runs`)
  if (!response.ok) {
    throw await failure(response, '텍스트를 불러오지 못했습니다')
  }
  return response.json()
}

/** The page's table borders (points from its top-left), to copy a table into a spreadsheet's cells. */
export async function fetchRulings(documentId: string, pageIndex: number): Promise<Rulings> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/rulings`)
  if (!response.ok) {
    throw await failure(response, '표의 선을 읽지 못했습니다')
  }
  return response.json()
}

let fontsRequest: Promise<FontChoice[]> | null = null

/** The font families installed on this computer; fetched once. */
export function fetchFonts(): Promise<FontChoice[]> {
  fontsRequest ??= call(`${BASE}/fonts`)
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
  spans: Span[]
  dx: number
  dy: number
}

/** A picture the server keeps for a document; its pixel size tells how big to draw it at first. */
export interface UploadedImage {
  imageId: string
  width: number
  height: number
}

export async function uploadImage(documentId: string, file: File): Promise<UploadedImage> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await call(`${BASE}/documents/${documentId}/images`, { method: 'POST', body: formData })
  if (!response.ok) {
    throw await failure(response, `그림을 올리지 못했습니다: ${file.name}`)
  }
  return response.json()
}

export function imageUrl(documentId: string, imageId: string): string {
  return `${BASE}/documents/${documentId}/images/${imageId}`
}

/** Replaces the pictures placed on a page with this list. */
export async function saveAddedImages(documentId: string, pageIndex: number, boxes: AddedImage[]): Promise<void> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/added-images`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(boxes),
  })
  if (!response.ok) {
    throw await failure(response, '그림을 저장하지 못했습니다')
  }
}

/** Replaces the text boxes added to a page with this list. */
export async function saveAddedTexts(documentId: string, pageIndex: number, boxes: AddedText[]): Promise<void> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/added-texts`, {
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
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/text-edits`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(items),
  })
  if (!response.ok) {
    throw await failure(response, '저장하지 못했습니다')
  }
}

export async function resetTextEdits(documentId: string, pageIndex: number): Promise<void> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/text-edits`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    throw await failure(response, '되돌리지 못했습니다')
  }
}

/** The text lines of `pages` that contain `query`. */
export async function findText(pages: PageItem[], query: string, matchCase: boolean): Promise<FindMatch[]> {
  const response = await call(`${BASE}/search`, {
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
  const response = await call(`${BASE}/replace`, {
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

/** A picture of the page as it was uploaded (without edits), `width` pixels wide. */
export function originalUrl(documentId: string, pageIndex: number, width: number): string {
  return `${BASE}/documents/${documentId}/pages/${pageIndex}/original?width=${Math.round(width)}`
}

export async function compareDocuments(a: string, b: string): Promise<CompareResult> {
  const response = await call(`${BASE}/compare`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ a, b }),
  })
  if (!response.ok) {
    throw await failure(response, '문서를 비교하지 못했습니다')
  }
  return response.json()
}

export async function compareLook(a: string, pageA: number, b: string, pageB: number): Promise<VisualResult> {
  const query = new URLSearchParams({ a, pageA: String(pageA), b, pageB: String(pageB) })
  const response = await call(`${BASE}/compare/visual?${query}`)
  if (!response.ok) {
    throw await failure(response, '모양을 비교하지 못했습니다')
  }
  return response.json()
}

export async function fetchRedactions(documentId: string, pageIndex: number): Promise<Redaction[]> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/redactions`)
  if (!response.ok) {
    throw await failure(response, '가림 영역을 불러오지 못했습니다')
  }
  return response.json()
}

export async function saveRedactions(documentId: string, pageIndex: number, areas: Redaction[]): Promise<void> {
  const response = await call(`${BASE}/documents/${documentId}/pages/${pageIndex}/redactions`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(areas),
  })
  if (!response.ok) {
    throw await failure(response, '가림 영역을 저장하지 못했습니다')
  }
}

export async function findPersonalInfo(
  pages: { documentId: string; pageIndex: number }[],
  kinds: PersonalInfoKind[],
  term: string,
): Promise<PersonalInfoMatch[]> {
  const response = await call(`${BASE}/personal-info`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      pages: pages.map((p) => ({ documentId: p.documentId, pageIndex: p.pageIndex, rotation: 0 })),
      kinds,
      term,
    }),
  })
  if (!response.ok) {
    throw await failure(response, '개인정보를 찾지 못했습니다')
  }
  return response.json()
}

/**
 * The exported file, and what its redaction did (null from a server without the report). With a lock, the
 * file is encrypted with that password and allows what `permissions` allows (everything when undefined). With
 * `compress` ("용량 줄이기") its pictures are stored again smaller, and `shrink` says by how much.
 */
export async function exportPdf(
  pages: PageItem[],
  fileName: string,
  flatten = false,
  lock?: { password: string; permissions?: number },
  compress = false,
): Promise<{ blob: Blob; redaction: RedactionReport | null; shrink: ShrinkReport | null }> {
  const response = await call(`${BASE}/export`, {
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
      compress,
      ...(lock ? { password: lock.password, permissions: lock.permissions ?? null } : {}),
    }),
  })
  if (!response.ok) {
    throw await failure(response, '내보내기 실패')
  }
  return {
    blob: await response.blob(),
    redaction: parseRedactionReport(response.headers.get('X-Redaction-Report')),
    shrink: parseShrinkReport(response.headers.get('X-Shrink-Report')),
  }
}

/**
 * 사진으로 내보내기: the pages as they would be exported (edits, redaction, rotation) as pictures - one picture
 * for one page, a ZIP of them for more. `stem` is the name without an extension.
 */
export async function exportImages(
  pages: PageItem[],
  stem: string,
  format: 'jpg' | 'png',
  dpi: number,
): Promise<{ blob: Blob; redaction: RedactionReport | null }> {
  const response = await call(`${BASE}/export/images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      pages: pages.map((p) => ({ documentId: p.documentId, pageIndex: p.pageIndex, rotation: p.rotation })),
      fileName: stem,
      format,
      dpi,
    }),
  })
  if (!response.ok) {
    throw await failure(response, '사진으로 내보내지 못했습니다')
  }
  return {
    blob: await response.blob(),
    redaction: parseRedactionReport(response.headers.get('X-Redaction-Report')),
  }
}
