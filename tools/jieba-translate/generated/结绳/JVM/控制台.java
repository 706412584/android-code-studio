package 结绳.JVM;

import java.util.Scanner;

public class 控制台 {
private static Scanner scanner;
static {
scanner = new Scanner(System.in);
}

public void 输出行(Object 欲输出内容) {
System.out.println(欲输出内容);
}

public void 输出(Object 欲输出内容) {
System.out.print(欲输出内容);
}

public void 输出格式文本(String 欲格式化文本, Object[] 格式化参数) {
System.out.printf(欲格式化文本,格式化参数);
}

public String 取输入文本() {
return (scanner.next());
}

public int 取输入整数() {
return (scanner.nextInt());
}

public double 取输入小数() {
return (scanner.nextDouble());
}

public float 取输入浮点数() {
return (scanner.nextFloat());
}

public long 取输入长整数() {
return (scanner.nextLong());
}

public boolean 取输入逻辑值() {
return (scanner.nextBoolean());
}

public boolean 是否有下一行() {
return (scanner.hasNext());
}
}