package ticode.zh.jvm;


public class 大数字 extends java.math.BigDecimal {
public Object 赋值_op(String 值) {
return new java.math.BigDecimal(值);
}

public 大数字 加_op(大数字 另一个大数字) {
return 加(另一个大数字);
}

public 大数字 减_op(大数字 另一个大数字) {
return 减(另一个大数字);
}

public 大数字 乘_op(大数字 另一个大数字) {
return 乘(另一个大数字);
}

public 大数字 除_op(大数字 另一个大数字) {
return 除以(另一个大数字);
}




public 大数字 加(大数字 另一个大数字) {
return this.add(另一个大数字);
}




public 大数字 减(大数字 另一个大数字) {
return this.subtract(另一个大数字);
}




public 大数字 乘(大数字 另一个大数字) {
return this.multiply(另一个大数字);
}




public 大数字 除以(大数字 另一个大数字) {
return this.divide(另一个大数字);
}
}