import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchSettings, saveSettings, UNREACHABLE, uploadDocument } from './api'

afterEach(() => {
  vi.unstubAllGlobals()
})

const answer = (status: number, body: string) => vi.fn(async () => new Response(body, { status }))

describe('api errors are said in Korean', () => {
  it("shows the server's own Korean message", async () => {
    vi.stubGlobal('fetch', answer(400, '보관 기간은 1일에서 3650일 사이로 정할 수 있습니다.'))

    await expect(saveSettings({ retentionDays: 0 })).rejects.toThrow(
      '설정을 저장하지 못했습니다: 보관 기간은 1일에서 3650일 사이로 정할 수 있습니다.',
    )
  })

  it("puts the web server's English error page into Korean words", async () => {
    vi.stubGlobal('fetch', answer(500, '{"timestamp":"2026-11-01","status":500,"error":"Internal Server Error"}'))

    const error = await fetchSettings().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(500)
    expect((error as ApiError).message).toBe('설정을 불러오지 못했습니다: 앱에서 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('says in Korean that the app cannot be reached, as a status that is no refusal', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))

    const error = await fetchSettings().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(0)
    expect((error as ApiError).message).toBe(UNREACHABLE)
  })

  it('refuses a file over the size limit before sending it', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const big = new File(['%PDF'], '도면.pdf', { type: 'application/pdf' })
    Object.defineProperty(big, 'size', { value: 201 * 1024 * 1024 })

    const error = await uploadDocument(big).catch((e: unknown) => e)

    expect((error as ApiError).status).toBe(413)
    expect((error as ApiError).message).toContain('도면.pdf: 파일이 너무 큽니다 (한 파일에 200MB까지')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends a file of 150 MB: up to 200 MB is taken', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ documentId: 'd1', fileName: '준공도서.pdf', pageCount: 1 })))
    vi.stubGlobal('fetch', fetchMock)
    const big = new File(['%PDF'], '준공도서.pdf', { type: 'application/pdf' })
    Object.defineProperty(big, 'size', { value: 150 * 1024 * 1024 })

    await uploadDocument(big).catch(() => undefined)

    expect(fetchMock).toHaveBeenCalled()
  })
})
