import { ElMessageBox } from 'element-plus'
import { t } from '@/locales'

export async function confirmLogout(onConfirmed: () => Promise<void>) {
  try {
    await ElMessageBox.confirm(t('确定退出当前账号吗？'), t('退出登录'), {
      confirmButtonText: t('退出'),
      cancelButtonText: t('取消'),
      type: 'warning',
      closeOnClickModal: false
    })
  } catch {
    return
  }
  await onConfirmed()
}
