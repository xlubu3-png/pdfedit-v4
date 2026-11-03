import { exportFileName } from './exportName'

export type ImageFormat = 'jpg' | 'png'

export interface ImageChoice {
  format: ImageFormat
  dpi: number
}

/** How sharp the pictures are: dots per inch of the page. */
export const IMAGE_SHARPNESS: { dpi: number; label: string; note: string }[] = [
  { dpi: 100, label: '보통', note: '메신저·화면용, 가장 작음' },
  { dpi: 200, label: '선명하게', note: '대부분의 용도' },
  { dpi: 300, label: '아주 선명하게', note: '인쇄용, 큼' },
]

export const DEFAULT_IMAGE_CHOICE: ImageChoice = { format: 'jpg', dpi: 200 }

/**
 * The name the pictures are saved under, without an extension: the export's name ("계약서_수정본"), and
 * for one page picked out of several, which one ("계약서_수정본_3쪽").
 */
export function imageExportStem(
  target: { key: string; documentId: string; sourceFileName: string }[],
  all: { key: string }[],
): string {
  const stem = exportFileName(target).replace(/\.pdf$/i, '')
  if (target.length !== 1 || all.length <= 1) return stem
  const position = all.findIndex((p) => p.key === target[0].key)
  return position < 0 ? stem : `${stem}_${position + 1}쪽`
}

/** The file that arrives: one picture for one page, a ZIP of pictures for more. */
export function imageExportFileName(stem: string, pageCount: number, format: ImageFormat): string {
  return pageCount === 1 ? `${stem}.${format}` : `${stem}.zip`
}
