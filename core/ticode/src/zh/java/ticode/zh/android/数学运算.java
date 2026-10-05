package ticode.zh.android;

import java.io.*;
import java.util.*;
import java.lang.reflect.*;
import android.view.*;
import android.util.*;
import android.net.*;
import android.database.*;
import android.provider.*;
import android.content.*;
import android.content.res.*;
import android.os.*;
import android.system.*;
import android.graphics.*;
import android.app.*;
import java.util.regex.*;
import java.net.*;
import java.math.*;

import ticode.zh.base.异常;
import ticode.zh.base.数字;
import ticode.zh.base.文本;
import ticode.zh.base.长整数;
import ticode.zh.jvm.UUID;
import ticode.zh.jvm.哈希表;
import ticode.zh.jvm.键值对;
import ticode.zh.jvm.集合;

public class 数学运算 {

public static final double E = 2.718281828459045d;
public static final double PI = 3.141592653589793d;

// 取余，也就是取模，取余(10，3)，得到结果为1
public static int 取余(int 值, int 余) {
return 值 % 余;
}

//求一个数的反正切值
public static double 求反正切(double 值) {
return Math.atan(值);
}

//求一个数的余弦值
public static double 求余弦(double 值) {
return Math.cos(值);
}

//求一个数的反对数
public static double 求反对数(double 值) {
return Math.exp(值);
}

//求一个数的自然对数
public static double 求自然对数(double 值) {
return Math.log(值);
}




public static int 取随机数(int 最小值, int 最大值) {
return (int)(Math.random() * (最大值 + 1- 最小值) + 最小值);
}




public static double 取随机小数(double 最小值, double 最大值) {
return (Math.random() * (最大值 + 1- 最小值) + 最小值);
}

//求一个数的正切值
public static double 求正弦(double 值) {
return Math.sin(值);
}

//获取数的符号，如果参数小于0，则返回-1.0。 如果参数大于零，则返回1.0；如果参数为正零或负零，则将其作为结果返回。
public static int 取符号(double 值) {
return (int) Math.signum(值);
}

//绝对值运算
public static double 取绝对值(double 值) {
return Math.abs(值);
}

//乘方运算,即求底数的指数次方
public static double 求次方(double 底数, double 指数) {
return Math.pow(底数,指数);
}

//求一个数的平方根
public static double 求平方根(double 值) {
return Math.sqrt(值);
}

//开n次方根，n为根指数
public static double 求次方根(double 底数, double 根指数) {
return 求次方(底数,1.0/根指数);
}

//求一个数的正切值
public static double 求正切(double 值) {
return Math.tan(值);
}

//取两个数中最小的数
public static double 取最小值(double 数1, double 数2) {
return Math.min(数1, 数2);
}

//取两个数中最大的数
public static double 取最大值(double 数1, double 数2) {
return Math.max(数1, 数2);
}

//将角度值转化为弧度值
public static double 角度转弧度(double 值) {
return Math.toRadians(值);
}

//将弧度值转化为角度值
public static double 弧度转角度(double 值) {
return Math.toDegrees(值);
}

//将一个数四舍五入，参数一为将要四舍五人的数字，参数二为小数点后几位
public static double 四舍五入(double 数字, int 精确度) {
return new java.math.BigDecimal(String.valueOf(数字)).setScale(精确度, 4).doubleValue();
}

//类似于高斯取整函数，取小于或等于该数字的最大整数
public static double 向下取整(double 数字) {
return Math.floor(数字);
}

//类似于高斯取整函数，取小于或等于该数字的最大整数,返回整数值
public static int 向下取整_整数值(double 数字) {
return 向下取整(数字).到整数();
}

//求一个数的反正弦值
public static double 求反正弦(double 值) {
return Math.asin(值);
}

//求一个数的反余弦值
public static double 求反余弦(double 值) {
return Math.acos(值);
}

//求计算表达式计算结果
public static double 计算表达式(String 表达式) {
double num[] = new double[20];
int flag = 0, begin = 0, end = 0, now;
now = -1;
Stack<Character> st = new Stack<Character>();
for (int i = 0; i < 表达式.length(); i++) {
char s = 表达式.charAt(i);
if (s == ' ') {

} else if (s == '+' || s == '-' || s == '*' || s == '/' || s == '(' || s == ')' || s == '%') {
if (flag == 1) {
now += 1;
if (end < begin) {
num[now] = Integer.valueOf(表达式.substring(begin, begin + 1));
} else {
num[now] = Integer.valueOf(表达式.substring(begin, end + 1));
}
flag = 0;
}
if (s == '-') {
if (i == 0) {
flag = 1;
begin = 0;
} else if (表达式.charAt(i - 1) == '(' || 表达式.charAt(i - 1) == '*'
|| 表达式.charAt(i - 1) == '/') {
flag = 1;
begin = i;
} else {
if (st.empty()) {
st.push(s);
} else if (s == ')') {
num[now - 1] = compute(num[now - 1], num[now], st.pop());
now -= 1;
st.pop();
} else if (s == '(') {
st.push(s);
} else if (priority(s) <= priority(st.peek())) {
num[now - 1] = compute(num[now - 1], num[now], st.pop());
now -= 1;
st.push(s);
} else {
st.push(s);
}
}
} else if (st.empty()) {
st.push(s);
} else if (s == ')') {
num[now - 1] = compute(num[now - 1], num[now], st.pop());
now -= 1;
st.pop();
} else if (s == '(') {
st.push(s);
} else if (priority(s) <= priority(st.peek())) {
num[now - 1] = compute(num[now - 1], num[now], st.pop());
now -= 1;
st.push(s);
} else {
st.push(s);
}

} else if (flag == 0) {
flag = 1;
begin = i;
} else {
end = i;
}

}
if (flag == 1) {
now += 1;
if (end < begin) {
num[now] = Integer.valueOf(表达式.substring(begin, begin + 1));
} else {
num[now] = Integer.valueOf(表达式.substring(begin, end + 1));
}
}
while (now > 0) {
num[now - 1] = compute(num[now - 1], num[now], st.pop());
now -= 1;
}
return num[0];
}

private static int priority(char s) {
switch (s) {
case '(':
case ')':
return 0;
case '-':
case '+':
return 1;
case '*':
case '%':
case '/':
return 2;
default:
return -1;

}
}

private static double compute(double num1, double num2, char s) {
switch (s) {
case '(':
case ')':
return 0;
case '-':
return num1 - num2;
case '+':
return num1 + num2;
case '%':
return num1 % num2;
case '*':
return num1 * num2;
case '/':
return num1 / num2;
default:
return 0;

}
}
}