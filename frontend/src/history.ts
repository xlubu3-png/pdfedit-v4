/**
 * Undo/redo for a sequence of whole-state snapshots. A snapshot is the state *before* a change, so
 * undo hands back the state as it was and remembers the current one for redo.
 *
 * Changes to the same thing in quick succession (typing in the size box, nudging with the arrow
 * keys) share a key and count as one step.
 */
export class History<T> {
  private undoStack: T[] = []
  private redoStack: T[] = []
  private lastKey = ''
  private lastTime = 0
  private readonly limit: number
  private readonly coalesceMs: number

  constructor(limit = 100, coalesceMs = 800) {
    this.limit = limit
    this.coalesceMs = coalesceMs
  }

  /** Call before a change with the state as it is now. Returns true when it started a new undo step. */
  record(key: string, snapshot: T, now = Date.now()): boolean {
    const continuation = key === this.lastKey && now - this.lastTime < this.coalesceMs
    this.lastKey = key
    this.lastTime = now
    if (continuation) return false
    this.undoStack.push(snapshot)
    if (this.undoStack.length > this.limit) this.undoStack.shift()
    this.redoStack = []
    return true
  }

  /** The state to go back to, or undefined when there is nothing to undo. */
  undo(current: T): T | undefined {
    const previous = this.undoStack.pop()
    if (previous === undefined) return undefined
    this.redoStack.push(current)
    this.lastKey = ''
    return previous
  }

  redo(current: T): T | undefined {
    const next = this.redoStack.pop()
    if (next === undefined) return undefined
    this.undoStack.push(current)
    this.lastKey = ''
    return next
  }

  get undoCount() {
    return this.undoStack.length
  }

  get redoCount() {
    return this.redoStack.length
  }
}
