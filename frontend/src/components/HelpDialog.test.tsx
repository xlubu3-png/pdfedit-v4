// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { editorTipSeen, markEditorTipSeen } from '../editorTip'
import { SHORTCUT_GROUPS } from '../shortcuts'
import { HelpDialog } from './HelpDialog'

afterEach(cleanup)
beforeEach(() => localStorage.clear())

describe('HelpDialog', () => {
  it('lists every shortcut group with its keys and what they do', () => {
    render(<HelpDialog onClose={() => {}} />)

    for (const group of SHORTCUT_GROUPS) {
      expect(screen.getByText(group.title)).toBeTruthy()
    }
    expect(screen.getByText('실행 취소')).toBeTruthy()
    expect(screen.getAllByText('Ctrl').length).toBeGreaterThan(5)
  })

  it('closes from the close button and from a click outside the window', () => {
    const onClose = vi.fn()
    const { container } = render(<HelpDialog onClose={onClose} />)

    fireEvent.click(screen.getAllByText('닫기').at(-1)!)
    fireEvent.mouseDown(container.querySelector('.find-backdrop')!)

    expect(onClose).toHaveBeenCalledTimes(2)
  })
})

describe('shortcut table', () => {
  it('gives every shortcut keys and a label, and no group is empty', () => {
    for (const group of SHORTCUT_GROUPS) {
      expect(group.items.length).toBeGreaterThan(0)
      for (const item of group.items) {
        expect(item.keys.length).toBeGreaterThan(0)
        expect(item.label).not.toBe('')
      }
    }
  })
})

describe('editor tip', () => {
  it('shows until it was closed once, then stays away', () => {
    expect(editorTipSeen()).toBe(false)

    markEditorTipSeen()

    expect(editorTipSeen()).toBe(true)
  })
})
