package cn.edu.cczu.course.module.learning.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 生成带 BOM 的 CSV，同时阻止文本单元格被表格软件解释为公式。 */
public final class CsvWriter {
    // 这是用于组织相关类型或静态方法的工具类，无需创建实例。
    private CsvWriter() { }
    // 把一个值编码成 CSV 单元格，并阻止文本被当成公式执行。
    public static String cell(Object value) {
        String text=value==null?"":value.toString();
        // 文本以表格程序会识别为公式的字符开头时，将它按普通文本输出。数字仍按原值生成。
        if(value instanceof CharSequence) {
            String leading=text.stripLeading();
            if((!leading.isEmpty()&&"=+-@".indexOf(leading.charAt(0))>=0)||text.indexOf('\t')>=0||text.startsWith("\r")||text.startsWith("\n"))text="'"+text;
        }
        // 用一对双引号包住字段并把内嵌引号写成两个，即使字段含逗号或换行也可被 CSV 正确读取。
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    // 先写入标题，再逐行写入数据，最后输出 UTF-8 字节。
    public static byte[] write(List<String> headers,List<List<Object>> rows) {
        // 文件开头写入 UTF-8 BOM，帮助表格软件识别中文编码。
        var result=new StringBuilder("\uFEFF");
        result.append(headers.stream().map(CsvWriter::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for(var row:rows) result.append(row.stream().map(CsvWriter::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
}
