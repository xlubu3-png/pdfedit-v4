import { describe, expect, it } from 'vitest'
import { clampZoom, fitPageZoom, fitZoom, MAX_ZOOM, MIN_ZOOM, previewDpi, PX_PER_PT_AT_100, zoomStep } from './zoom'

describe('fitPageZoom', () => {
  it('shows a whole A4 page (595 x 842pt) in a wide, low window: the height decides', () => {
    // 700px high, 48px breathing room: 652 / (842 * 4/3) = 0.58
    expect(fitPageZoom(1400, 700, 595, 842)).toBeCloseTo(0.58, 2)
    expect(fitPageZoom(1400, 700, 595, 842)).toBeLessThan(fitZoom(1400, 595))
  })

  it('in a narrow, tall window the width decides, as with fitting the width', () => {
    expect(fitPageZoom(500, 2000, 595, 842)).toBe(fitZoom(500, 595))
  })

  it('stays in the allowed range and copes with a page of no size', () => {
    expect(fitPageZoom(10, 10, 5000, 5000)).toBe(MIN_ZOOM)
    expect(fitPageZoom(1000, 800, 0, 0)).toBe(1)
  })
})

describe('zoom', () => {
  it('fits an A4 page (595pt) to a window', () => {
    // 1000px window, 48px breathing room: 952 / (595 * 4/3) = 1.2
    expect(fitZoom(1000, 595)).toBeCloseTo(1.2, 2)
    expect(fitZoom(400, 595)).toBeLessThan(0.6)
  })

  it('never goes outside the allowed range', () => {
    expect(fitZoom(100000, 100)).toBe(MAX_ZOOM)
    expect(fitZoom(10, 5000)).toBe(MIN_ZOOM)
    expect(clampZoom(0)).toBe(MIN_ZOOM)
    expect(clampZoom(99)).toBe(MAX_ZOOM)
  })

  it('steps through round values and stops at the ends', () => {
    expect(zoomStep(1, 1)).toBe(1.1)
    expect(zoomStep(1, -1)).toBe(0.9)
    expect(zoomStep(1.2, 1)).toBe(1.25)
    expect(zoomStep(1.2, -1)).toBe(1.1)
    expect(zoomStep(MAX_ZOOM, 1)).toBe(MAX_ZOOM)
    expect(zoomStep(MIN_ZOOM, -1)).toBe(MIN_ZOOM)
  })

  it('asks for as many pixels as the screen can show, and no more', () => {
    const at100 = PX_PER_PT_AT_100
    expect(previewDpi(at100, 1)).toBe(96)
    expect(previewDpi(at100 * 1.5, 1)).toBe(150)
    expect(previewDpi(at100, 2)).toBe(200)
    expect(previewDpi(at100 * 4, 2)).toBe(300)
  })
})
