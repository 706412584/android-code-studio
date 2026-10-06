import ticode.zh.jvm.ZIP操作;
import ticode.zh.jvm.反射操作;

import java.io.File;
import java.nio.file.Files;

/** 第二批冒烟测试：ZIP 往返 + 反射操作（都是刚「具体化」的壳类）。 */
public class SmokeTest2 {
    static int pass = 0, fail = 0;
    static void eq(String n, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        if (ok) { pass++; System.out.println("  PASS  " + n); }
        else { fail++; System.out.println("  FAIL  " + n + "\n        got : " + got + "\n        want: " + want); }
    }

    public static class Bean {
        private String name = "init";
        public String getName() { return name; }
        public int add(int a, int b) { return a + b; }
    }

    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("ticode-smoke").toFile();

        System.out.println("== ZIP 往返 ==");
        try {
            File src = new File(tmp, "a.txt");
            Files.write(src.toPath(), "zip 测试内容 hello".getBytes("UTF-8"));
            String zipPath = new File(tmp, "t.zip").getAbsolutePath();
            String outDir = new File(tmp, "out").getAbsolutePath();
            ZIP操作.压缩文件(src.getAbsolutePath(), zipPath);
            eq("zip 文件已生成", new File(zipPath).exists(), true);
            ZIP操作.解压文件(zipPath, outDir);
            File got = new File(outDir, "a.txt");
            eq("解压出的文件存在", got.exists(), true);
            if (got.exists()) eq("内容一致", new String(Files.readAllBytes(got.toPath()), "UTF-8"), "zip 测试内容 hello");
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL  ZIP 抛异常: " + t);
            t.printStackTrace();
        }

        System.out.println("== 反射操作 ==");
        try {
            Bean b = new Bean();
            eq("读私有字段 name", 反射操作.获取字段值(Bean.class.getName(), b, "name"), "init");
            boolean set = 反射操作.设置字段值(Bean.class.getName(), b, "name", "changed");
            eq("设置字段值 返回 true", set, true);
            eq("设置后 读取", 反射操作.获取字段值(Bean.class.getName(), b, "name"), "changed");
            eq("调用 getName()", 反射操作.执行无参方法(Bean.class.getName(), b, "getName"), "changed");
            eq("调用 add(2,3)", 反射操作.执行方法(Bean.class.getName(), b, "add",
                    new Class[]{int.class, int.class}, new Object[]{2, 3}), 5);
        } catch (Throwable t) {
            fail++; System.out.println("  FAIL  反射 抛异常: " + t);
            t.printStackTrace();
        }

        System.out.println();
        System.out.println("==== 结果: PASS=" + pass + "  FAIL=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
