package ticode.android;


public class RecordSet implements android.database.Cursor {
public int 总数() {
return this.getCount();
}

public boolean 下一个() {
return this.moveToNext();
}

public String 取文本(String 键名) {
return this.getString(this.getColumnIndex(键名));
}

public int 取整数(String 键名) {
return this.getInt(this.getColumnIndex(键名));
}

public long 取长整数(String 键名) {
return this.getLong(this.getColumnIndex(键名));
}

public double 取小数(String 键名) {
return this.getDouble(this.getColumnIndex(键名));
}

public float 取单精度小数(String 键名) {
return this.getFloat(this.getColumnIndex(键名));
}

public boolean 到开头() {
return this.moveToFirst();
}

public boolean 到最后() {
return this.moveToLast();
}

public int 当前位置() {
return this.getPosition();
}

public boolean 移动位置(int 位置) {
return this.moveToPosition(位置);
}
}