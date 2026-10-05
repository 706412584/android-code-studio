package ticode.jvm;


public class BigInteger2 extends java.math.BigInteger {

public Object 赋值_op(String 值, int 基数) {
return new java.math.BigInteger(值,基数);
}

public BigInteger2 加_op(BigInteger2 另一个大整数) {
return 加(另一个大整数);
}

public BigInteger2 减_op(BigInteger2 另一个大整数) {
return 减(另一个大整数);
}

public BigInteger2 乘_op(BigInteger2 另一个大整数) {
return 乘(另一个大整数);
}

public BigInteger2 除_op(BigInteger2 另一个大整数) {
return 除以(另一个大整数);
}




public BigInteger2 加(BigInteger2 另一个大整数) {
return this.add(另一个大整数);
}




public BigInteger2 减(BigInteger2 另一个大整数) {
return this.subtract(另一个大整数);
}




public BigInteger2 乘(BigInteger2 另一个大整数) {
return this.multiply(另一个大整数);
}




public BigInteger2 除以(BigInteger2 另一个大整数) {
return this.divide(另一个大整数);
}

public String 到文本(int 要转到的进制) {
return this.toString(要转到的进制);
}

}