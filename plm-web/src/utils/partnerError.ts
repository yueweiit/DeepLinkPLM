import {isAxiosError} from 'axios'
export function partnerError(error:unknown){if(isAxiosError(error)&&typeof error.response?.data?.message==='string')return error.response.data.message;return error instanceof Error?error.message:'保存失败，请检查网络后重试'}
