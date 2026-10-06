package ticode.zh.jvm;


public abstract class 大整数 extends java.math.BigInteger {

public 大整数(String 值, int 基数) { super(值,基数); }

public 大整数 加_op(大整数 另一个大整数) {
return (大整数)加(另一个大整数);
}

public 大整数 减_op(大整数 另一个大整数) {
return (大整数)减(另一个大整数);
}

public 大整数 乘_op(大整数 另一个大整数) {
return (大整数)乘(另一个大整数);
}

public 大整数 除_op(大整数 另一个大整数) {
return (大整数)除以(另一个大整数);
}




public 大整数 加(大整数 另一个大整数) {
return (大整数)this.add(另一个大整数);
}




public 大整数 减(大整数 另一个大整数) {
return (大整数)this.subtract(另一个大整数);
}




public 大整数 乘(大整数 另一个大整数) {
return (大整数)this.multiply(另一个大整数);
}




public 大整数 除以(大整数 另一个大整数) {
return (大整数)this.divide(另一个大整数);
}

public String 到文本(int 要转到的进制) {
return this.toString(要转到的进制);
}

}