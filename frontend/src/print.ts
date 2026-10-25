/** How long the hidden frame is kept after the print dialog opened: the dialog reads from it while open. */
const KEEP_MS = 10 * 60 * 1000

/**
 * Prints a PDF with the browser's own print dialog: the PDF is loaded into a hidden frame (the browser's PDF
 * viewer) and that frame is printed, so what comes out of the printer is the PDF itself, sharp at any size.
 * When the browser cannot print from the frame, the PDF opens in a new tab to be printed from there.
 */
export function printPdf(blob: Blob, doc: Document = document): Promise<void> {
  const url = URL.createObjectURL(blob)
  const frame = doc.createElement('iframe')
  frame.className = 'print-frame'
  frame.setAttribute('aria-hidden', 'true')
  frame.title = '인쇄할 문서'
  // Not display:none: a hidden frame has no layout, and some browsers then print an empty page.
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:1px;height:1px;border:0;opacity:0;pointer-events:none'
  const cleanUp = () => {
    frame.remove()
    URL.revokeObjectURL(url)
  }
  return new Promise((resolve) => {
    frame.addEventListener(
      'load',
      () => {
        try {
          const view = frame.contentWindow
          if (!view) throw new Error('no frame')
          view.focus()
          view.print()
        } catch {
          doc.defaultView?.open(url, '_blank')
        }
        setTimeout(cleanUp, KEEP_MS)
        resolve()
      },
      { once: true },
    )
    frame.src = url
    doc.body.appendChild(frame)
  })
}
