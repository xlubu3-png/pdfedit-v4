const KEY = 'wintech-pdf-editor-tip-seen'

/** Whether the short "how to use" tip of the editor was shown and closed before. */
export function editorTipSeen(): boolean {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    return false
  }
}

/** Remembers that the tip was closed, so it does not come back every time the editor opens. */
export function markEditorTipSeen(): void {
  try {
    localStorage.setItem(KEY, '1')
  } catch {
    // private mode or storage full: the tip simply shows again next time
  }
}
