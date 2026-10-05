import { describe, expect, it } from 'vitest'
import { snapPosition } from './snap'

const targets = { xs: [72, 300], ys: [700, 650] }

describe('snapPosition', () => {
  it('pulls a position onto a nearby line and reports the guide', () => {
    const snapped = snapPosition(74, 652, targets, 4)

    expect(snapped).toEqual({ x: 72, y: 650, guideX: 72, guideY: 650 })
  })

  it('leaves a position alone when no line is close', () => {
    const snapped = snapPosition(150, 500, targets, 4)

    expect(snapped).toEqual({ x: 150, y: 500, guideX: null, guideY: null })
  })

  it('snaps each axis on its own', () => {
    const snapped = snapPosition(73, 500, targets, 4)

    expect(snapped.x).toBe(72)
    expect(snapped.guideX).toBe(72)
    expect(snapped.y).toBe(500)
    expect(snapped.guideY).toBeNull()
  })

  it('takes the nearest line when two are in reach', () => {
    const snapped = snapPosition(301, 0, { xs: [296, 303], ys: [] }, 6)

    expect(snapped.x).toBe(303)
  })

  it('does nothing without lines', () => {
    expect(snapPosition(10, 10, { xs: [], ys: [] }, 5)).toEqual({ x: 10, y: 10, guideX: null, guideY: null })
  })
})
