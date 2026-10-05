/**
 * Characters of an edited line that the page's own font cannot draw. A subset font only holds the
 * characters the document used, so these are drawn in an installed font and can look different from
 * the rest of the line (usually thinner or heavier).
 */
export function foreignChars(text: string, ownChars: string): string {
  const own = new Set(ownChars)
  const found = new Set<string>()
  for (const ch of text) {
    if (!/\s/.test(ch) && !own.has(ch)) found.add(ch)
  }
  return [...found].join('')
}
