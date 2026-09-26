// A message is the smallest unit of a conversation.
// The role identifies the speaker and determines bubble alignment and color:
//   user      → right-aligned, pink background
//   assistant → left-aligned, white background
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
}

// ===== User profile =====

// Gender options plus an empty string for no selection.
export type Gender = '' | 'Male' | 'Female' | 'Other'

// Profile field names match the backend's persisted JSON structure.
// Age is number | null; other fields are strings, with an empty string meaning unspecified.
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


// ===== Food records =====

// General intake information matches the backend GenericInfo record.
// Numeric fields are JSON numbers and can be null.
export interface GenericInfo {
  foodName: string | null
  amount: number | null
  unit: string | null
  calories: number | null
  date: string | null
}

// Macronutrients match the backend MacroNutrients record.
export interface MacroNutrients {
  protein: number | null
  carbs: number | null
  fat: number | null
  fiber: number | null
}

// A food intake entry matches the backend Food record.
export interface Food {
  genericInfo: GenericInfo | null
  macroNutrients: MacroNutrients | null
}

// A persisted food entry includes its database ID and matches FoodEntry.
// Delete entries by ID with DELETE /api/food/{id}.
export interface FoodEntry {
  id: number
  food: Food
}
