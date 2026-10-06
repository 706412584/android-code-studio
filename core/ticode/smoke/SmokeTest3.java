import ticode.zh.android.数组操作;
import ticode.zh.android.转换操作;
import ticode.zh.jvm.UUID;
import ticode.zh.jvm.正则表达式;
import ticode.zh.jvm.整数集合;
import ticode.zh.jvm.文本到文本哈希表;

/**
 * 第三批冒烟测试：数组 / 转换 / 正则 / 集合族 / UUID。
 *
 * <p>都是纯 JVM 可跑的类（不触 Android 运行时），断言基于已知值。
 */
public class SmokeTest3 {
    static int pass = 0, fail = 0;

    static void eq(String n, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        if (ok) { pass++; System.out.println("  PASS  " + n); }
        else { fail++; System.out.println("  FAIL  " + n + "\n        got : " + got + "\n        want: " + want); }
    }

    static void ok(String n, boolean c) {
        if (c) { pass++; System.out.println("  PASS  " + n); }
        else { fail++; System.out.println("  FAIL  " + n); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 数组操作 ==");
        eq("取数组长度(Object[])", 数组操作.取数组长度(new Object[]{"a", "b", "c"}), 3);
        eq("取数组长度(byte[])", 数组操作.取数组长度(new byte[]{1, 2}), 2);
        eq("取数组最大数", 数组操作.取数组最大数(new int[]{3, 9, 2, 7}), 9);
        int[] sorted = 数组操作.数组冒泡排序(new int[]{3, 1, 2});
        eq("数组冒泡排序", sorted[0] + "," + sorted[1] + "," + sorted[2], "1,2,3");
        eq("数组到集合 size", 数组操作.数组到集合(new Object[]{"x", "y"}).size(), 2);

        System.out.println("== 转换操作 ==");
        eq("整数到字节", 转换操作.整数到字节(65), (byte) 65);
        eq("字节集到十六进制", 转换操作.字节集到十六进制(new byte[]{0x0F, (byte) 0xA0}), "0FA0");
        eq("十六进制到字节集", 转换操作.十六进制到字节集("0FA0")[0], (byte) 0x0F);
        eq("整数到字节集→字节集到整数 往返", 转换操作.字节集到整数(转换操作.整数到字节集(123456)), 123456);
        eq("长整数往返", 转换操作.字节集到长整数(转换操作.长整数到字节集(9876543210L)), 9876543210L);
        eq("文本到字节集→字节集到文本 往返",
            转换操作.字节集到文本(转换操作.文本到字节集("中文abc", "UTF-8"), "UTF-8"), "中文abc");
        eq("中文转Unicode", 转换操作.中文转Unicode("中"), "\\u4e2d");
        eq("Unicode转中文", 转换操作.Unicode转中文("\\u4e2d"), "中");

        System.out.println("== 正则表达式 ==");
        String[] m = 正则表达式.正则匹配("a1b2c3", "\\d", 0);
        eq("正则匹配 数量", m.length, 3);
        eq("正则匹配 首个", m[0], "1");
        ok("正则匹配2 非空", !正则表达式.正则匹配2("x9y", "\\d", 0).isEmpty());
        java.util.regex.Pattern p = 正则表达式.编译("(\\w+)=(\\d+)");
        java.util.regex.Matcher matcher = p.matcher("key=42");
        ok("编译出的 Pattern 可匹配", matcher.find());
        eq("分组1", matcher.group(1), "key");
        eq("分组2", matcher.group(2), "42");
        eq("正则分割", 正则表达式.编译(",").split("a,b,c").length, 3);

        System.out.println("== 集合族（模板类实例）==");
        整数集合 ic = new 整数集合();
        ic.添加成员(null);  // 元素是 整数类 壳，用 null 只验结构
        eq("整数集合 size", ic.size(), 1);
        文本到文本哈希表 h = new 文本到文本哈希表();
        h.添加项目("k", "v");
        eq("哈希表 取项目", h.取项目("k"), "v");

        System.out.println("== UUID ==");
        ok("取随机标识符 非空", UUID.取随机标识符() != null);
        ok("两次随机不同", !UUID.取随机标识符().equals(UUID.取随机标识符()));

        System.out.println();
        System.out.println("==== 结果: PASS=" + pass + "  FAIL=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
