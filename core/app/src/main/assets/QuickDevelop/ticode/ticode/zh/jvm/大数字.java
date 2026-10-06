package ticode.zh.jvm;


public class 大数字 extends java.math.BigDecimal {
public 大数字(String 值) { super(值); }

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
// BigDecimal 是不可变基类，this.add() 返回的是原生 BigDecimal，不能强转成子类壳
// （会 ClassCastException）。用文本重建壳对象。
return new 大数字(this.add(另一个大数字).toString());
}




public 大数字 减(大数字 另一个大数字) {
return new 大数字(this.subtract(另一个大数字).toString());
}




public 大数字 乘(大数字 另一个大数字) {
return new 大数字(this.multiply(另一个大数字).toString());
}




public 大数字 除以(大数字 另一个大数字) {
return new 大数字(this.divide(另一个大数字).toString());
}
}