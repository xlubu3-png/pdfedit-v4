/** Hangul syllables: the app's own messages, from this page and from its server, are written in Korean. */
const HANGUL = /[가-힣]/

export function isKorean(text: string): boolean {
  return HANGUL.test(text)
}

/**
 * What to tell the user about a failure: its own message when it is one of the app's (in Korean), otherwise
 * `fallback`. So a browser's or a library's English ("Failed to fetch", "Unexpected token '<'") never reaches the
 * screen.
 */
export function errorText(e: unknown, fallback: string): string {
  const message = e instanceof Error ? e.message : typeof e === 'string' ? e : ''
  return isKorean(message) ? message : fallback
}
