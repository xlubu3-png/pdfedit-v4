/**
 * The PDFs among dropped or chosen files. The browser reports no type for some files (it depends on
 * what Windows knows about ".pdf"), so the name counts too.
 */
export function pickPdfs(files: File[]): { pdfs: File[]; skipped: string[] } {
  const pdfs: File[] = []
  const skipped: string[] = []
  for (const file of files) {
    if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) pdfs.push(file)
    else skipped.push(file.name)
  }
  return { pdfs, skipped }
}

/** Whether a drag carries files from outside the page (a page card being reordered does not). */
export function dragHasFiles(types: readonly string[] | undefined): boolean {
  return Array.from(types ?? []).includes('Files')
}
