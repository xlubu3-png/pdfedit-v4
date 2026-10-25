/**
 * The PDFs among dropped or chosen files. The browser reports no type for some files (it depends on
 * what Windows knows about ".pdf"), so the name counts too.
 */
export function pickPdfs(files: File[]): { pdfs: File[]; hwps: File[]; skipped: string[] } {
  const pdfs: File[] = []
  const hwps: File[] = []
  const skipped: string[] = []
  for (const file of files) {
    if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) pdfs.push(file)
    else if (isHwp(file.name)) hwps.push(file)
    else skipped.push(file.name)
  }
  return { pdfs, hwps, skipped }
}

/** A Hangul word processor document (HWP 5.0 or HWPX), opened in the HWP editor rather than the page list. */
export function isHwp(name: string): boolean {
  return /\.hwpx?$/i.test(name)
}

/** What the file chooser offers: PDFs and Hangul documents. */
export const OPENABLE_FILES = 'application/pdf,.pdf,.hwp,.hwpx'

/** Whether a drag carries files from outside the page (a page card being reordered does not). */
export function dragHasFiles(types: readonly string[] | undefined): boolean {
  return Array.from(types ?? []).includes('Files')
}
