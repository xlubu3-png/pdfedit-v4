/** How long the file's address stays valid: the browser may still be reading a big file after the click. */
const KEEP_MS = 60_000

/**
 * Saves `blob` as `fileName` through the browser's download. The address is given back only a while later:
 * revoked at once, Firefox and Safari can drop the download of a large file before it has started.
 */
export function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.style.display = 'none'
  // Firefox follows only a link that is part of the page.
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), KEEP_MS)
}
