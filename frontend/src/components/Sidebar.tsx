// 导航目标：目前只有「聊天」「设置」两页，纯 React state 切换，不引入路由。
export type View = 'chat' | 'settings'

interface SidebarProps {
  current: View
  onNavigate: (view: View) => void
}

interface NavItemDef {
  id: View
  label: string
  icon: string
}

const NAV_ITEMS: NavItemDef[] = [
  { id: 'chat', label: '聊天', icon: '💬' },
  { id: 'settings', label: '设置', icon: '⚙️' },
]

// 左侧导航栏：渲染导航项 + 当前项高亮。
// 纯展示组件，导航状态由父组件 App 持有并下发，点击只回调 onNavigate。
export default function Sidebar({ current, onNavigate }: SidebarProps) {
  return (
    <nav className="sidebar">
      <span className="sidebar__label">导航</span>
      {NAV_ITEMS.map((item) => (
        <button
          key={item.id}
          type="button"
          className={`nav-item ${current === item.id ? 'nav-item--active' : ''}`}
          onClick={() => onNavigate(item.id)}
        >
          <span className="nav-item__icon">{item.icon}</span>
          {item.label}
        </button>
      ))}
    </nav>
  )
}
