import { fontBaseName, fontSearchUrl, knownSource } from '../fontHelp'

interface MissingFontDialogProps {
  /** The fonts this page uses that are not installed (as the PDF names them); empty once they all are. */
  fonts: string[]
  /** What the last reload found, if one was done. */
  message: string | null
  /** Reading the fonts again is under way. */
  busy: boolean
  onReload: () => void
  onClose: () => void
}

/**
 * Says which fonts of the page this PC lacks and how to get them. The app does not download fonts by
 * itself: they have owners and licences, and installing one is the user's decision. It points to where
 * the font usually comes from, then reads the fonts again so the new one is used straight away.
 */
export function MissingFontDialog({ fonts, message, busy, onReload, onClose }: MissingFontDialogProps) {
  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        className="find-dialog font-help"
        role="dialog"
        aria-modal="true"
        aria-label="이 PC에 없는 글꼴"
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <div className="find-head">
          <b>이 PC에 없는 글꼴</b>
          <button type="button" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </div>

        {fonts.length === 0 ? (
          <p className="font-ok">✓ 이 페이지에서 쓰는 글꼴이 모두 이 PC에 설치되어 있습니다.</p>
        ) : (
          <>
            <p className="find-note">
              이 페이지는 아래 글꼴로 만들어졌는데 이 PC에는 없습니다. 이미 쓰인 글자는 그대로 두지만, 새로 입력하는 글자는 비슷한
              다른 글꼴로 그려져 모양이 달라질 수 있습니다. 글꼴을 설치하면 새 글자도 원본과 똑같이 나옵니다.
            </p>
            <ul className="font-list">
              {fonts.map((font) => {
                const source = knownSource(font)
                return (
                  <li key={font}>
                    <b>{fontBaseName(font)}</b>
                    <span className="font-links">
                      {source?.url && (
                        <a href={source.url} target="_blank" rel="noopener noreferrer">
                          {source.label}에서 받기
                        </a>
                      )}
                      <a href={fontSearchUrl(font)} target="_blank" rel="noopener noreferrer">
                        웹에서 찾아보기
                      </a>
                    </span>
                    {source && <small>{source.note}</small>}
                  </li>
                )
              })}
            </ul>
            <ol className="font-steps">
              <li>
                위 링크에서 글꼴 파일(<b>.ttf</b> 또는 .ttc)을 받습니다. 압축 파일이면 먼저 풉니다. (.otf만 있는 글꼴은 이 앱이 아직
                읽지 못합니다.)
              </li>
              <li>
                글꼴 파일을 더블클릭해 <b>설치</b>를 누릅니다.
              </li>
              <li>
                여기로 돌아와 <b>글꼴 다시 읽기</b>를 누릅니다. 앱을 다시 켤 필요는 없습니다.
              </li>
            </ol>
            <p className="find-note">글꼴에는 저작권이 있어서 앱이 대신 내려받거나 설치하지 않습니다. 사용 조건은 받는 곳에서 확인하세요.</p>
          </>
        )}

        {message && (
          <p className="find-message" aria-live="polite">
            {message}
          </p>
        )}
        <div className="find-actions">
          <button type="button" className="primary" onClick={onReload} disabled={busy}>
            {busy ? '읽는 중…' : '글꼴 다시 읽기'}
          </button>
          <button type="button" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}
