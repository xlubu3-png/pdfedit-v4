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
