// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { printPdf } from './print'

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

URL.createObjectURL ??= () => ''
URL.revokeObjectURL ??= () => {}

describe('printPdf', () => {
  it('loads the PDF into a hidden frame and prints that frame', async () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:doc')
    const done = printPdf(new Blob(['%PDF-'], { type: 'application/pdf' }))
    const frame = document.querySelector('iframe.print-frame') as HTMLIFrameElement
    expect(frame.src).toBe('blob:doc')
    const print = vi.fn()
    Object.defineProperty(frame, 'contentWindow', { value: { focus: vi.fn(), print } })

    frame.dispatchEvent(new Event('load'))
    await done

    expect(print).toHaveBeenCalledTimes(1)
  })

  it('opens the PDF in a new tab when the frame cannot print', async () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:doc')
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const done = printPdf(new Blob(['%PDF-']))
    const frame = document.querySelector('iframe.print-frame') as HTMLIFrameElement
    Object.defineProperty(frame, 'contentWindow', {
      value: {
        focus: vi.fn(),
        print: () => {
          throw new Error('blocked')
        },
      },
    })

    frame.dispatchEvent(new Event('load'))
    await done

    expect(open).toHaveBeenCalledWith('blob:doc', '_blank')
  })
})
