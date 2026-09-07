// 消息数据契约：整条聊天记录的最小单元。
// role 决定这条消息是谁说的，进而决定气泡的对齐方式与配色：
//   user      → 靠右、粉色底
//   assistant → 靠左、白色底
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
}

// ===== 用户信息 =====

// 性别：三选一 + 空串（未选择）。
export type Gender = '' | '男' | '女' | '其他'

// 用户信息契约：字段名与后端落盘 JSON 完全一致（用户指定的结构）。
//   User.Age 为 number | null（空 = 未填写），其余字段均为 string（空串 = 未填写）。
export interface UserInfo {
  User: {
    Name: string
    Gender: Gender
    Age: number | null
    JobType: string
  }
  Other: {
    Info: string
    expectationFromLLM: string
  }
}
