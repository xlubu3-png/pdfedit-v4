/**
 * The PDFs among dropped or chosen files. The browser reports no type for some files (it depends on
 * what Windows knows about ".pdf"), so the name counts too.
 */
export function pickPdfs(files: File[]): { pdfs: File[]; hwps: File[]; skipped: string[] } {
  const pdfs: File[] = []
  const hwps: File[] = []
  const skipped: string[] = []
  for (const file of files) {
    // Word, Excel and PowerPoint files go up like PDFs: the app has them printed to PDF by the PC's Office. Pictures
    // (photos, scans and faxes saved as pictures) too: a page each.
    if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name) || isOffice(file.name) || isImage(file.name)) pdfs.push(file)
    else if (isHwp(file.name)) hwps.push(file)
    else skipped.push(file.name)
  }
  return { pdfs, hwps, skipped }
}

/** A Hangul word processor document (HWP 5.0 or HWPX), opened in the HWP editor rather than the page list. */
export function isHwp(name: string): boolean {
  return /\.hwpx?$/i.test(name)
}

/** A Word, Excel or PowerPoint document, opened as a PDF printed by the PC's Microsoft Office. */
export function isOffice(name: string): boolean {
  return /\.(docx?|docm|rtf|odt|xlsx?|xlsm|xlsb|csv|ods|pptx?|pptm|ppsx?|odp)$/i.test(name)
}

/** A picture (JPEG, PNG, TIFF of several pages, BMP, GIF), opened as a PDF of a page per picture, like a scan. */
export function isImage(name: string): boolean {
  return /\.(jpe?g|png|gif|bmp|tiff?)$/i.test(name)
}

/** What the file chooser offers: PDFs, Hangul documents, Office documents and pictures. */
export const OPENABLE_FILES =
  'application/pdf,.pdf,.hwp,.hwpx,.doc,.docx,.rtf,.odt,.xls,.xlsx,.xlsm,.csv,.ods,.ppt,.pptx,.pps,.ppsx,.odp,' +
  '.jpg,.jpeg,.png,.gif,.bmp,.tif,.tiff'

/** Whether a drag carries files from outside the page (a page card being reordered does not). */
export function dragHasFiles(types: readonly string[] | undefined): boolean {
  return Array.from(types ?? []).includes('Files')
}
