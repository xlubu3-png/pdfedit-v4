// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { addRightClickHandler, WAIT_MS } from './rightClick'

const removers: (() => void)[] = []
const cleanups: (() => void)[] = []

beforeEach(() => {
  document.body.innerHTML = '<div id="area"><span id="inside">글자</span></div><div id="elsewhere"></div>'
})

afterEach(() => {
  removers.splice(0).forEach((remove) => remove())
  cleanups.splice(0).forEach((cleanup) => cleanup())
  vi.useRealTimers()
})

/** A part of the page with a menu of its own: everything in #area. */
function areaMenu() {
  const opened: { id: string; blocked: boolean }[] = []
  removers.push(
    addRightClickHandler((target, _x, _y, blocked) => {
      if (!target.closest('#area')) return false
      opened.push({ id: target.id, blocked })
      return true
    }),
  )
  return opened
}

/** Stand-in for a browser extension that takes right clicks away from the page. */
function extension(on: EventTarget, stop: (e: Event) => void) {
  on.addEventListener('contextmenu', stop, true)
  cleanups.push(() => on.removeEventListener('contextmenu', stop, true))
}

const rightClick = (el: Element) => el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 }))
const button = (type: 'pointerdown' | 'pointerup', el: Element) =>
  el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, button: 2, clientX: 5, clientY: 5 }))

describe('right clicks taken at the window', () => {
  it("opens the area's menu instead of the browser's", () => {
    const opened = areaMenu()

    const browserMenuShown = rightClick(document.getElementById('inside')!)

    expect(browserMenuShown).toBe(false)
    expect(opened).toEqual([{ id: 'inside', blocked: false }])
  })

  it("leaves a click outside every area to the browser's menu", () => {
    const opened = areaMenu()

    expect(rightClick(document.getElementById('elsewhere')!)).toBe(true)
    expect(opened).toEqual([])
  })

  it('still gets the click when an extension stops it at the document', () => {
    const opened = areaMenu()
    extension(document, (e) => e.stopPropagation())

    expect(rightClick(document.getElementById('inside')!)).toBe(false)
    expect(opened).toEqual([{ id: 'inside', blocked: false }])
  })

  it('opens the menu when the button is let go if the right click never arrives', async () => {
    vi.useFakeTimers()
    // An extension's listener comes first (it is set up before the page's scripts) and swallows the event whole.
    extension(window, (e) => e.stopImmediatePropagation())
    vi.resetModules()
    const fresh = await import('./rightClick')
    const opened: { id: string; blocked: boolean }[] = []
    removers.push(
      fresh.addRightClickHandler((target, _x, _y, blocked) => {
        opened.push({ id: target.id, blocked })
        return true
      }),
    )
    const inside = document.getElementById('inside')!
    const elementFromPoint = vi.fn(() => inside)
    Object.assign(document, { elementFromPoint })

    button('pointerdown', inside)
    rightClick(inside)
    button('pointerup', inside)
    expect(opened).toEqual([])
    vi.advanceTimersByTime(fresh.WAIT_MS)

    expect(elementFromPoint).toHaveBeenCalledWith(5, 5)
    expect(opened).toEqual([{ id: 'inside', blocked: true }])
  })

  it('does not open a second menu when the right click did arrive', () => {
    vi.useFakeTimers()
    const opened = areaMenu()
    const inside = document.getElementById('inside')!
    Object.assign(document, { elementFromPoint: () => inside })

    button('pointerdown', inside)
    rightClick(inside)
    button('pointerup', inside)
    vi.advanceTimersByTime(WAIT_MS)

    expect(opened).toEqual([{ id: 'inside', blocked: false }])
  })
})
