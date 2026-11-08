export const MIN_ZOOM = 0.25
export const MAX_ZOOM = 4
/** CSS pixels per PDF point at 100%: a PDF point is 1/72 inch and a CSS inch is 96 pixels. */
export const PX_PER_PT_AT_100 = 96 / 72

const STEPS = [0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4]
/** Resolutions the server renders previews at; asking for a few fixed ones keeps the images cacheable. */
const PREVIEW_DPI_STEPS = [96, 150, 200, 300]

export function clampZoom(zoom: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom))
}

/** The zoom at which a page of `pageWidthPt` just fills `containerWidthPx` (minus some breathing room). */
export function fitZoom(containerWidthPx: number, pageWidthPt: number, padding = 48): number {
  if (pageWidthPt <= 0) return 1
  const zoom = (containerWidthPx - padding) / (pageWidthPt * PX_PER_PT_AT_100)
  return clampZoom(Math.round(zoom * 100) / 100)
}

/** The zoom at which the whole page, its height too, fits in the container: "쪽 맞춤". */
export function fitPageZoom(
  containerWidthPx: number,
  containerHeightPx: number,
  pageWidthPt: number,
  pageHeightPt: number,
  padding = 48,
): number {
  if (pageWidthPt <= 0 || pageHeightPt <= 0) return 1
  const byHeight = clampZoom(Math.floor(((containerHeightPx - padding) / (pageHeightPt * PX_PER_PT_AT_100)) * 100) / 100)
  return Math.min(fitZoom(containerWidthPx, pageWidthPt, padding), byHeight)
}

/** The next zoom step in or out of `zoom`. */
export function zoomStep(zoom: number, direction: 1 | -1): number {
  if (direction > 0) return STEPS.find((s) => s > zoom + 0.001) ?? MAX_ZOOM
  return [...STEPS].reverse().find((s) => s < zoom - 0.001) ?? MIN_ZOOM
}

/** The preview resolution that is sharp on this screen at this scale, without rendering more than needed. */
export function previewDpi(pxPerPt: number, devicePixelRatio: number): number {
  const needed = pxPerPt * 72 * Math.max(1, devicePixelRatio)
  return PREVIEW_DPI_STEPS.find((dpi) => dpi >= needed - 1) ?? PREVIEW_DPI_STEPS[PREVIEW_DPI_STEPS.length - 1]
}

/** How the editor shows the page: 'fit' to the window's width, 'page' whole; a number is a fixed zoom (1 = 100%). */
export type ZoomMode = 'fit' | 'page' | number

const ZOOM_KEY = 'wintech-pdf-zoom'
/** The zoom last chosen in the editor: the next page, and the editor opened again, keep it. */
let rememberedMode: ZoomMode | null = null

export function rememberedZoom(): ZoomMode {
  if (rememberedMode !== null) return rememberedMode
  try {
    const stored = localStorage.getItem(ZOOM_KEY)
    if (stored === 'fit' || stored === 'page') return stored
    const n = Number(stored)
    if (stored !== null && Number.isFinite(n) && n > 0) return clampZoom(n)
  } catch {
    // no storage (private window): the editor starts at the window's width
  }
  return 'fit'
}

export function rememberZoom(mode: ZoomMode): void {
  rememberedMode = mode
  try {
    localStorage.setItem(ZOOM_KEY, String(mode))
  } catch {
    // kept for this run only
  }
}

/** Forgets the remembered zoom (for tests). */
export function forgetZoom(): void {
  rememberedMode = null
  try {
    localStorage.removeItem(ZOOM_KEY)
  } catch {
    // nothing kept
  }
}
