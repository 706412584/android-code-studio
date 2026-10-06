import ticode.zh.android.加解密操作;
import ticode.zh.jvm.GZIP操作;
import ticode.zh.jvm.集合;
import ticode.zh.jvm.哈希表;
import ticode.zh.jvm.双端队列;

/**
 * ticode 中文版冒烟测试：验证手工修复后运行时行为正确。
 * 纯 JDK 可测的类；不依赖 JUnit，用简单断言。
 */
public class SmokeTest {
    static int pass = 0, fail = 0;

    static void eq(String name, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + "\n        got : " + got + "\n        want: " + want); }
    }

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 加解密操作 ==");
        eq("MD5(\"abc\")", 加解密操作.MD5加密("abc", "UTF-8"),
           "900150983cd24fb0d6963f7d28e17f72");
        eq("MD5(\"\")", 加解密操作.MD5加密("", "UTF-8"),
           "d41d8cd98f00b204e9800998ecf8427e");
        eq("Base64(\"hello\")", 加解密操作.Base64编码("hello", "UTF-8", 加解密操作.Base64编码集),
           "aGVsbG8=");
        eq("Base64(\"Hello, World!\")", 加解密操作.Base64编码("Hello, World!", "UTF-8", 加解密操作.Base64编码集),
           "SGVsbG8sIFdvcmxkIQ==");

        System.out.println("== GZIP 往返 ==");
        try {
            byte[] 原文 = "结绳移植测试 hello gzip 12345".getBytes("UTF-8");
            byte[] 压缩 = GZIP操作.压缩字节集(原文);
            byte[] 解压 = GZIP操作.解压字节集(压缩);
            eq("压缩后长度 != 原文", 压缩.length != 原文.length, true);
            eq("解压 == 原文", new String(解压, "UTF-8"), new String(原文, "UTF-8"));
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL  GZIP 抛异常: " + t);
        }

        System.out.println("== 集合 (ArrayList 包装) ==");
        集合 c = new 集合();
        c.添加成员("a"); c.添加成员("b");
        eq("size()", c.size(), 2);
        eq("取成员(0)", c.取成员(0), "a");
        eq("是否存在(\"b\")", c.是否存在("b"), true);
        eq("是否存在(\"z\")", c.是否存在("z"), false);

        System.out.println("== 哈希表 (HashMap 包装) ==");
        哈希表 h = new 哈希表();
        h.添加项目("k1", "v1");
        eq("取项目(k1)", h.取项目("k1"), "v1");
        eq("是否存在(k1)", h.是否存在("k1"), true);
        eq("长度()", h.长度(), 1);
        h.删除项目("k1");
        eq("删除后 是否存在", h.是否存在("k1"), false);

        System.out.println("== 双端队列 (ArrayDeque 包装) ==");
        try {
            双端队列 d = new 双端队列();
            ok("可构造", d != null);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL  双端队列 构造抛异常: " + t);
        }

        System.out.println();
        System.out.println("==== 结果: PASS=" + pass + "  FAIL=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
