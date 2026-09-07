// 消息数据契约：整条聊天记录的最小单元。
// role 决定这条消息是谁说的，进而决定气泡的对齐方式与配色：
//   user      → 靠右、粉色底
//   assistant → 靠左、白色底
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
}
