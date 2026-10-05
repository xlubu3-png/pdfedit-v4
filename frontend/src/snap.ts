/** Lines other text boxes sit on, in PDF points: left edges (xs) and baselines (ys). */
export interface SnapTargets {
  xs: number[]
  ys: number[]
}

export interface Snapped {
  x: number
  y: number
  /** The x / y the box was pulled onto (to draw a guide line there), or null when it did not snap. */
  guideX: number | null
  guideY: number | null
}

function nearest(value: number, candidates: number[], tolerance: number): number | null {
  let best: number | null = null
  let bestDistance = tolerance
  for (const candidate of candidates) {
    const distance = Math.abs(candidate - value)
    if (distance <= bestDistance) {
      best = candidate
      bestDistance = distance
    }
  }
  return best
}

/** Pulls a position onto the nearest line within `tolerance` points, separately for x and y. */
export function snapPosition(x: number, y: number, targets: SnapTargets, tolerance: number): Snapped {
  const guideX = nearest(x, targets.xs, tolerance)
  const guideY = nearest(y, targets.ys, tolerance)
  return { x: guideX ?? x, y: guideY ?? y, guideX, guideY }
}
