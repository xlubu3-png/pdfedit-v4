import { shortNotes } from '../updateNotice'
import type { UpdateInfo } from '../types'

interface UpdateBannerProps {
  info: UpdateInfo
  /** 'installing': downloading and starting the installer; 'launched': the installer is up and the app is about to quit. */
  state: 'idle' | 'installing' | 'launched'
  onInstall: () => void
  onSkip: () => void
}

/** Tells the user that a newer version exists and offers to install it. */
export function UpdateBanner({ info, state, onInstall, onSkip }: UpdateBannerProps) {
  if (state === 'launched') {
    return (
      <div className="info-banner update-banner" role="status">
        <span>
          설치 프로그램을 실행했습니다. 이 앱은 곧 종료됩니다. 설치가 끝나면 바탕화면(또는 시작 메뉴)의 WINTECH_PDF로 다시 시작하세요.
          작업 중이던 문서는 저장되어 있어 그대로 이어집니다.
        </span>
      </div>
    )
  }
  const notes = shortNotes(info.notes)
  return (
    <div className="info-banner update-banner" role="status">
      <span>
        <b>새 버전 v{info.latest}</b>이(가) 있습니다 (지금 v{info.current}).{notes && <> {notes}</>}
        {info.releaseUrl && (
          <>
            {' '}
            <a href={info.releaseUrl} target="_blank" rel="noopener noreferrer">
              변경 내용 보기
            </a>
          </>
        )}
      </span>
      <button type="button" className="primary" onClick={onInstall} disabled={state === 'installing'}>
        {state === 'installing' ? '내려받는 중…' : '지금 업데이트'}
      </button>
      <button type="button" onClick={onSkip} disabled={state === 'installing'} title="이 버전은 다시 알리지 않습니다">
        나중에
      </button>
    </div>
  )
}
