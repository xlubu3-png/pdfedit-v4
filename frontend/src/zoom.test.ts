import { describe, expect, it } from 'vitest'
import { clampZoom, fitZoom, MAX_ZOOM, MIN_ZOOM, previewDpi, PX_PER_PT_AT_100, zoomStep } from './zoom'

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
