// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PasswordDialog } from './PasswordDialog'

afterEach(cleanup)

describe('PasswordDialog', () => {
  it('sends the typed password, and only once something was typed', () => {
    const onSubmit = vi.fn()
    render(<PasswordDialog fileName="a.pdf" wrong={false} onSubmit={onSubmit} onCancel={() => {}} />)
    const open = screen.getByText('열기') as HTMLButtonElement

    expect(open.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('암호'), { target: { value: 'secret' } })
    fireEvent.click(open)

    expect(onSubmit).toHaveBeenCalledWith('secret')
  })

  it('says when the last password was wrong', () => {
    render(<PasswordDialog fileName="a.pdf" wrong onSubmit={() => {}} onCancel={() => {}} />)

    expect(screen.getByText(/암호가 맞지 않습니다/)).toBeTruthy()
  })

  it('gives up from the cancel button, Escape and a click outside', () => {
    const onCancel = vi.fn()
    const { container } = render(<PasswordDialog fileName="a.pdf" wrong={false} onSubmit={() => {}} onCancel={onCancel} />)

    fireEvent.click(screen.getByText('열지 않기'))
    fireEvent.keyDown(screen.getByLabelText('암호'), { key: 'Escape' })
    fireEvent.mouseDown(container.querySelector('.find-backdrop')!)

    expect(onCancel).toHaveBeenCalledTimes(3)
  })
})
