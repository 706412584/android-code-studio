package ticode.zh.jvm;


public abstract class 大数字 extends java.math.BigDecimal {
public Object 赋值_op(String 值) {
return new java.math.BigDecimal(值);
}

public 大数字 加_op(大数字 另一个大数字) {
return (大数字)加(另一个大数字);
}

public 大数字 减_op(大数字 另一个大数字) {
return (大数字)减(另一个大数字);
}

public 大数字 乘_op(大数字 另一个大数字) {
return (大数字)乘(另一个大数字);
}

public 大数字 除_op(大数字 另一个大数字) {
return (大数字)除以(另一个大数字);
}




public 大数字 加(大数字 另一个大数字) {
return (大数字)this.add(另一个大数字);
}




public 大数字 减(大数字 另一个大数字) {
return (大数字)this.subtract(另一个大数字);
}




public 大数字 乘(大数字 另一个大数字) {
return (大数字)this.multiply(另一个大数字);
}




public 大数字 除以(大数字 另一个大数字) {
return (大数字)this.divide(另一个大数字);
}
}