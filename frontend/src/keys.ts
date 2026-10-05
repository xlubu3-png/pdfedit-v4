/**
 * The position-based name of a pressed key ("KeyC"), which stays the same whatever the keyboard
 * layout or input mode is (in Korean input mode `key` is "ㅊ" while `code` is still "KeyC").
 * Where the browser gives no `code`, a typed letter stands in for it.
 */
export function keyCode(e: { code: string; key: string }): string {
  if (e.code) return e.code
  return e.key.length === 1 && /[a-z]/i.test(e.key) ? `Key${e.key.toUpperCase()}` : ''
}
