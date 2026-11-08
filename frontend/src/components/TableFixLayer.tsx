import { useEffect, useRef, useState } from 'react'
import type { MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent } from 'react'
import { splitArea } from '../borderless'
import { gridOf } from '../tableGrid'
import type { Grid, Table } from '../tableGrid'
import type { PlacedText } from '../textSelect'

/** A table being set out: its borders, and whether the user made or changed it (else it is the page's own). */
interface Item {
  grid: Grid
  manual: boolean
}

type Mode = 'move' | 'col' | 'row' | 'draw'

/** Borders closer than this (points) would make a cell too thin to hold anything. */
const MIN_CELL = 3
/** A new table smaller than this (points, either way) was a slip of the mouse. */
const MIN_TABLE = 12

interface TableFixLayerProps {
  /** The page's own tables (found on it), as they are used now. */
  found: Table[]
  /** The tables set out by hand on the page so far. */
  grids: Grid[]
  /** The page's text (points), for a new table to be split into its lines and columns. */
  texts: PlacedText[]
  /** Pixels per point on the screen. */
  scale: number
  onDone: (grids: Grid[]) => Promise<void>
  onCancel: () => void
}

/**
 * 표 칸 바로잡기: the page's tables shown by their borders, to be put right by hand where they were found wrong -
 * a border dragged to its place, one put in where a column or row was missed (세로선/가로선 넣기, then a click), one
 * taken out (a double click), a table drawn where none was found (새 표 그리기: its lines and columns are filled in
 * from the text in it). What is changed is kept with the page and used for Excel and for copying as a table.
 */
export function TableFixLayer({ found, grids, texts, scale, onDone, onCancel }: TableFixLayerProps) {
  const [items, setItems] = useState<Item[]>(() => [
    ...found.filter((t) => !grids.some((g) => same(g, gridOf(t)))).map((t) => ({ grid: gridOf(t), manual: false })),
    ...grids.map((g) => ({ grid: { xs: [...g.xs], ys: [...g.ys] }, manual: true })),
  ])
  const [mode, setMode] = useState<Mode>(items.length === 0 ? 'draw' : 'move')
  const [picked, setPicked] = useState<number | null>(null)
  const [drawing, setDrawing] = useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null)
  const [busy, setBusy] = useState(false)
  const drag = useRef<{ item: number; axis: 'xs' | 'ys'; index: number } | null>(null)
  const layer = useRef<HTMLDivElement>(null)

  const toPoints = (e: { clientX: number; clientY: number }) => {
    const r = layer.current!.getBoundingClientRect()
    return { x: (e.clientX - r.left) / scale, y: (e.clientY - r.top) / scale }
  }

  function change(index: number, grid: Grid) {
    setItems((prev) => prev.map((it, i) => (i === index ? { grid, manual: true } : it)))
  }

  function onLinePointerDown(e: ReactPointerEvent, item: number, axis: 'xs' | 'ys', index: number) {
    if (mode !== 'move') return
    e.stopPropagation()
    e.preventDefault()
    setPicked(item)
    drag.current = { item, axis, index }
    layer.current?.setPointerCapture(e.pointerId)
  }

  function removeLine(item: number, axis: 'xs' | 'ys', index: number) {
    const grid = items[item].grid
    const values = grid[axis]
    if (index === 0 || index === values.length - 1) return
    change(item, { ...grid, [axis]: values.filter((_, i) => i !== index) })
  }

  /**
   * A double click on a border takes it out. Heard here, not on the border: the drag its first press began holds
   * the pointer to this layer, so the click lands here.
   */
  function onDoubleClick(e: ReactMouseEvent) {
    if (mode !== 'move') return
    const line = document
      .elementsFromPoint(e.clientX, e.clientY)
      .find((el): el is HTMLElement => el instanceof HTMLElement && el.dataset.line !== undefined)
    const table = line?.closest<HTMLElement>('[data-table]')
    if (!line || !table) return
    const axis = line.dataset.line!.startsWith('x') ? 'xs' : 'ys'
    removeLine(Number(table.dataset.table), axis, Number(line.dataset.line!.slice(1)))
  }

  function onPointerDown(e: ReactPointerEvent) {
    if (e.button !== 0) return
    const p = toPoints(e)
    const hit = items.findIndex(
      ({ grid }) =>
        p.x >= grid.xs[0] && p.x <= grid.xs[grid.xs.length - 1] && p.y >= grid.ys[0] && p.y <= grid.ys[grid.ys.length - 1],
    )
    if (mode === 'draw') {
      setDrawing({ x0: p.x, y0: p.y, x1: p.x, y1: p.y })
      layer.current?.setPointerCapture(e.pointerId)
      return
    }
    setPicked(hit >= 0 ? hit : null)
    if (hit < 0) return
    const grid = items[hit].grid
    if (mode === 'col') {
      const xs = [...grid.xs, p.x].sort((a, b) => a - b)
      if (xs.every((x, i) => i === 0 || x - xs[i - 1] >= MIN_CELL)) change(hit, { ...grid, xs })
    } else if (mode === 'row') {
      const ys = [...grid.ys, p.y].sort((a, b) => a - b)
      if (ys.every((y, i) => i === 0 || y - ys[i - 1] >= MIN_CELL)) change(hit, { ...grid, ys })
    }
  }

  function onPointerMove(e: ReactPointerEvent) {
    const p = toPoints(e)
    if (drawing) {
      setDrawing({ ...drawing, x1: p.x, y1: p.y })
      return
    }
    const d = drag.current
    if (!d) return
    const grid = items[d.item].grid
    const values = [...grid[d.axis]]
    const low = d.index > 0 ? values[d.index - 1] + MIN_CELL : -Infinity
    const high = d.index < values.length - 1 ? values[d.index + 1] - MIN_CELL : Infinity
    values[d.index] = Math.min(high, Math.max(low, d.axis === 'xs' ? p.x : p.y))
    change(d.item, { ...grid, [d.axis]: values })
  }

  function onPointerUp() {
    drag.current = null
    if (!drawing) return
    const left = Math.min(drawing.x0, drawing.x1)
    const right = Math.max(drawing.x0, drawing.x1)
    const top = Math.min(drawing.y0, drawing.y1)
    const bottom = Math.max(drawing.y0, drawing.y1)
    setDrawing(null)
    if (right - left < MIN_TABLE || bottom - top < MIN_TABLE) return
    setItems((prev) => [...prev, { grid: splitArea(texts, left, top, right, bottom), manual: true }])
    setPicked(items.length)
    setMode('move')
  }

  // Delete takes out the picked table (one set out by hand; the page's own are put right, not taken out).
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
      if ((e.key === 'Delete' || e.key === 'Backspace') && picked !== null && items[picked]?.manual) {
        e.preventDefault()
        setItems((prev) => prev.filter((_, i) => i !== picked))
        setPicked(null)
      }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  })

  async function done() {
    setBusy(true)
    try {
      await onDone(items.filter((it) => it.manual).map((it) => it.grid))
    } finally {
      setBusy(false)
    }
  }

  const manualCount = items.filter((it) => it.manual).length
  const hint = {
    move: '선을 끌어 옮기고, 두 번 클릭해 지웁니다. 표를 골라 Delete로 직접 그린 표를 지웁니다.',
    col: '표 안을 클릭한 곳에 세로선(칸 경계)을 넣습니다.',
    row: '표 안을 클릭한 곳에 가로선(줄 경계)을 넣습니다.',
    draw: '표가 될 곳을 끌어 그리세요. 안의 글자 줄과 칸에 맞춰 나눠 드립니다.',
  }[mode]

  return (
    <>
      <div className="te-tablefix-bar" role="toolbar" aria-label="표 칸 바로잡기">
        <b>표 칸 바로잡기</b>
        {(['move', 'col', 'row', 'draw'] as const).map((m) => (
          <button key={m} type="button" className={mode === m ? 'active' : ''} aria-pressed={mode === m} onClick={() => setMode(m)}>
            {{ move: '선 옮기기', col: '세로선 넣기', row: '가로선 넣기', draw: '새 표 그리기' }[m]}
          </button>
        ))}
        <span className="te-tablefix-hint">{hint}</span>
        <button
          type="button"
          onClick={() => void onDone([])}
          disabled={busy || (grids.length === 0 && manualCount === 0)}
          title="직접 바로잡은 표를 모두 지우고, 쪽에서 찾은 표를 다시 씁니다"
        >
          자동으로 되돌리기
        </button>
        <button type="button" onClick={onCancel} disabled={busy}>
          취소
        </button>
        <button type="button" className="primary" onClick={() => void done()} disabled={busy}>
          완료
        </button>
      </div>
      <div
        ref={layer}
        className={`te-tablefix mode-${mode}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onDoubleClick={onDoubleClick}
      >
        {items.map(({ grid, manual }, i) => {
          const left = grid.xs[0] * scale
          const top = grid.ys[0] * scale
          const width = (grid.xs[grid.xs.length - 1] - grid.xs[0]) * scale
          const height = (grid.ys[grid.ys.length - 1] - grid.ys[0]) * scale
          return (
            <div
              key={i}
              className={`te-tablefix-table${manual ? ' manual' : ''}${picked === i ? ' picked' : ''}`}
              style={{ left, top, width, height }}
              data-table={i}
            >
              {grid.xs.map((x, k) => (
                <div
                  key={`x${k}`}
                  className="te-tablefix-line vertical"
                  data-line={`x${k}`}
                  style={{ left: (x - grid.xs[0]) * scale }}
                  onPointerDown={(e) => onLinePointerDown(e, i, 'xs', k)}
                  title={k === 0 || k === grid.xs.length - 1 ? '표의 가장자리: 끌어 옮깁니다' : '끌어 옮기고, 두 번 클릭해 지웁니다'}
                />
              ))}
              {grid.ys.map((y, k) => (
                <div
                  key={`y${k}`}
                  className="te-tablefix-line horizontal"
                  data-line={`y${k}`}
                  style={{ top: (y - grid.ys[0]) * scale }}
                  onPointerDown={(e) => onLinePointerDown(e, i, 'ys', k)}
                  title={k === 0 || k === grid.ys.length - 1 ? '표의 가장자리: 끌어 옮깁니다' : '끌어 옮기고, 두 번 클릭해 지웁니다'}
                />
              ))}
            </div>
          )
        })}
        {drawing && (
          <div
            className="te-tablefix-drawing"
            style={{
              left: Math.min(drawing.x0, drawing.x1) * scale,
              top: Math.min(drawing.y0, drawing.y1) * scale,
              width: Math.abs(drawing.x1 - drawing.x0) * scale,
              height: Math.abs(drawing.y1 - drawing.y0) * scale,
            }}
          />
        )}
      </div>
    </>
  )
}

function same(a: Grid, b: Grid): boolean {
  const close = (p: number[], q: number[]) => p.length === q.length && p.every((v, i) => Math.abs(v - q[i]) < 0.5)
  return close(a.xs, b.xs) && close(a.ys, b.ys)
}
