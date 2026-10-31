// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LockDialog } from './LockDialog'

afterEach(cleanup)

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

describe('LockDialog', () => {
  it('locks with the password once it is typed the same twice', () => {
    const onLock = vi.fn()
    render(<LockDialog fromProtected onLock={onLock} onPlain={vi.fn()} onCancel={vi.fn()} />)
    const lock = screen.getByRole('button', { name: '암호 걸어 내보내기' }) as HTMLButtonElement
    expect(lock.disabled).toBe(true)

    type('암호', 'secret')
    type('암호 확인', 'secreT')
    expect(lock.disabled).toBe(true)
    expect(screen.getByText('두 암호가 다릅니다.')).toBeTruthy()

    type('암호 확인', 'secret')
    fireEvent.click(lock)
    expect(onLock).toHaveBeenCalledWith('secret')
  })

  it('offers an export without a password for a document that came protected', () => {
    const onPlain = vi.fn()
    render(<LockDialog fromProtected onLock={vi.fn()} onPlain={onPlain} onCancel={vi.fn()} />)
    expect(screen.getByText(/원래 문서에 암호가 걸려 있었습니다/)).toBeTruthy()

    fireEvent.click(screen.getByText('암호 없이 내보내기'))
    expect(onPlain).toHaveBeenCalled()
  })

  it('asked for from the menu, it only locks or gives up', () => {
    const onCancel = vi.fn()
    render(<LockDialog fromProtected={false} onLock={vi.fn()} onPlain={vi.fn()} onCancel={onCancel} />)

    expect(screen.queryByText('암호 없이 내보내기')).toBeNull()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onCancel).toHaveBeenCalled()
  })
})
