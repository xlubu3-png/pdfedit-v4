const BLANK_PAGE_NAME = '빈 페이지'

/**
 * The name to download the PDF as: the source file's name plus "_수정본" when the pages come from one
 * file (blank pages added by hand do not count), otherwise "merged.pdf".
 */
export function exportFileName(pages: { documentId: string; sourceFileName: string }[]): string {
  const sources = new Map<string, string>()
  for (const page of pages) {
    if (page.sourceFileName !== BLANK_PAGE_NAME) sources.set(page.documentId, page.sourceFileName)
  }
  if (sources.size !== 1) return 'merged.pdf'
  const [name] = [...sources.values()]
  const stem = name
    .replace(/\.pdf$/i, '')
    .replace(/[\\/:*?"<>|]/g, '_')
    .trim()
  return stem ? `${stem}_수정본.pdf` : 'merged.pdf'
}
