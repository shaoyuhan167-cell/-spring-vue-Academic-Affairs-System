// 调用后端导出接口，再让浏览器把返回的 CSV 保存为本地文件。
import { ElMessage } from 'element-plus'
import { exportApi } from '../api/modules'

// 等待后端文件内容后创建临时下载地址，触发保存并释放地址。
export async function downloadCsv(scope, resource, params = {}, filename = '选课系统数据') {
  try {
    const blob = await exportApi(scope, resource, params)
    // 使用浏览器临时文件地址触发下载，下载链接用完后释放地址，避免长时间占用内存。
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${filename}_${new Date().toISOString().slice(0, 10)}.csv`
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success('已导出 CSV，可使用 Excel 打开')
  } catch (exception) { ElMessage.error(exception.message) }
}
