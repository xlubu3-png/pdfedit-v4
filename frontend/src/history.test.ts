import { describe, expect, it } from 'vitest'
import { History } from './history'

describe('History', () => {
  it('undoes to the state before the change and redoes it again', () => {
    const history = new History<string>()
    history.record('a', 'start', 0)

    expect(history.undo('after-a')).toBe('start')
    expect(history.redo('start')).toBe('after-a')
    expect(history.undoCount).toBe(1)
    expect(history.redoCount).toBe(0)
  })

  it('has nothing to undo or redo when empty', () => {
    const history = new History<string>()

    expect(history.undo('x')).toBeUndefined()
    expect(history.redo('x')).toBeUndefined()
  })

  it('treats quick changes to the same thing as one step', () => {
    const history = new History<number>(100, 800)

    expect(history.record('size', 12, 0)).toBe(true)
    expect(history.record('size', 13, 200)).toBe(false)
    expect(history.record('size', 14, 400)).toBe(false)

    expect(history.undoCount).toBe(1)
    expect(history.undo(15)).toBe(12)
  })

  it('starts a new step after a pause, or for another thing', () => {
    const history = new History<number>(100, 800)

    history.record('size', 12, 0)
    expect(history.record('size', 14, 5000)).toBe(true)
    expect(history.record('move', 14, 5100)).toBe(true)

    expect(history.undoCount).toBe(3)
  })

  it('does not merge a change into the step that was just undone', () => {
    const history = new History<number>(100, 800)
    history.record('size', 12, 0)
    history.undo(14)

    expect(history.record('size', 12, 100)).toBe(true)
  })

  it('forgets the redo steps when something new is done', () => {
    const history = new History<string>()
    history.record('a', 's0', 0)
    history.undo('s1')
    expect(history.redoCount).toBe(1)

    history.record('b', 's0', 10_000)

    expect(history.redoCount).toBe(0)
  })

  it('keeps only the most recent steps up to the limit', () => {
    const history = new History<number>(3, 0)
    for (let i = 0; i < 10; i++) history.record(`k${i}`, i, i * 1000)

    expect(history.undoCount).toBe(3)
    expect(history.undo(99)).toBe(9)
    expect(history.undo(9)).toBe(8)
    expect(history.undo(8)).toBe(7)
    expect(history.undo(7)).toBeUndefined()
  })
})
