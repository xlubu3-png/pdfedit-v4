import { LICENSE_LINKS } from '../licenses'
import { HELP_BASICS, SHORTCUT_GROUPS } from '../shortcuts'

interface HelpDialogProps {
  onClose: () => void
}

/** How the editor is used and every keyboard shortcut, laid out as a table instead of one long paragraph. */
export function HelpDialog({ onClose }: HelpDialogProps) {
  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="find-dialog help-dialog" role="dialog" aria-modal="true" aria-label="도움말과 단축키">
        <div className="find-head">
          <b>도움말과 단축키</b>
          <button type="button" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </div>

        <section>
          <h3>사용 방법</h3>
          <ol className="help-basics">
            {HELP_BASICS.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
        </section>

        <section>
          <h3>단축키</h3>
          <div className="help-groups">
            {SHORTCUT_GROUPS.map((group) => (
              <div key={group.title} className="help-group">
                <h4>{group.title}</h4>
                <dl>
                  {group.items.map((item) => (
                    <div key={item.keys.join('+')} className="help-row">
                      <dt>
                        {item.keys.map((key, i) => (
                          <span key={key}>
                            {i > 0 && <span className="help-plus">+</span>}
                            <kbd>{key}</kbd>
                          </span>
                        ))}
                      </dt>
                      <dd>{item.label}</dd>
                    </div>
                  ))}
                </dl>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h3>오픈소스 라이선스</h3>
          <p className="help-note">이 프로그램에 들어 있는 오픈소스와 그 라이선스입니다. 새 탭에서 열립니다.</p>
          <ul className="help-licenses">
            {LICENSE_LINKS.map((link) => (
              <li key={link.href}>
                <a href={link.href} target="_blank" rel="noreferrer">
                  {link.label}
                </a>
              </li>
            ))}
          </ul>
        </section>

        <div className="find-actions">
          <button type="button" className="primary" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}
