/**
 * Puts text on the system clipboard. The Clipboard API needs a secure context, and the app may be opened over
 * plain http (on a NAS), so the old way through a hidden text field is the fallback.
 *
 * @returns whether the text got there
 */
export async function copyText(text: string): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // refused (no focus, no permission): try the old way
    }
  }
  const field = document.createElement('textarea')
  field.value = text
  field.setAttribute('readonly', '')
  field.style.position = 'fixed'
  field.style.opacity = '0'
  document.body.appendChild(field)
  field.select()
  let copied = false
  try {
    copied = document.execCommand('copy')
  } catch {
    copied = false
  }
  field.remove()
  return copied
}

/**
 * Puts text on the clipboard in two forms: HTML for programs that take tables (Excel, Hangul, Word - a table
 * pastes into its cells) and plain text for the rest (Notepad). Whether it worked.
 */
export async function copyRich(plain: string, html: string): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard && typeof ClipboardItem !== 'undefined') {
    try {
      await navigator.clipboard.write([
        new ClipboardItem({
          'text/plain': new Blob([plain], { type: 'text/plain' }),
          'text/html': new Blob([html], { type: 'text/html' }),
        }),
      ])
      return true
    } catch {
      // refused: try the old way
    }
  }
  // The old way: a copy the page answers itself, with both forms.
  const put = (e: ClipboardEvent) => {
    e.clipboardData?.setData('text/plain', plain)
    e.clipboardData?.setData('text/html', html)
    e.preventDefault()
  }
  document.addEventListener('copy', put, true)
  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.removeEventListener('copy', put, true)
  }
}
