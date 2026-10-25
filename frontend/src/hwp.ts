/** Where the app serves the HWP/HWPX editor (rhwp-studio, built by tools/build-hwp-studio.ps1). */
export const HWP_STUDIO_URL = '/hwp-studio/index.html'

export type HwpFormat = 'hwp' | 'hwpx'

/** The format a document came in: what saving keeps it in unless asked otherwise. */
export function formatOf(fileName: string): HwpFormat {
  return /\.hwp$/i.test(fileName) ? 'hwp' : 'hwpx'
}

/** The name a saved copy gets: the document's own name with the extension of the format it is saved in. */
export function savedName(fileName: string, format: HwpFormat): string {
  const base = fileName.replace(/\.(hwpx?|hml)$/i, '').trim() || '문서'
  return `${base}.${format}`
}
