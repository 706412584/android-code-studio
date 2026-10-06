package ticode.zh.jvm;


public class 文件 extends java.io.File {
public 文件(String 文件路径) { super(文件路径); }

public static java.io.File 从路径创建(String 路径) {
return (java.io.File)new java.io.File(路径);
}

public static java.io.File 新建对象(String 路径, String 子文件名) {
return (java.io.File)new java.io.File(路径, 子文件名);
}

public static java.io.File 新建对象2(java.io.File 目录, String 子文件名) {
return (java.io.File)new java.io.File(目录, 子文件名);
}

public String 取文件名() {
return this.getName();
}

public String 取父目录路径() {
return this.getParent();
}

public java.io.File 取父目录() {
return (java.io.File)this.getParentFile();
}

public String 取路径() {
return this.getPath();
}

public String 取绝对路径() {
return this.getAbsolutePath();
}

//判断当前文件是否为文件夹(目录)
public boolean 为文件夹() {
return this.isDirectory();
}

//判断当前文件是否为文件
public boolean 为文件() {
return this.isFile();
}





public boolean 新建文件() {
try {
return this.createNewFile();
} catch (java.io.IOException e) {
e.printStackTrace();
}
return false;
}





public boolean 新建文件夹() {
return this.mkdirs();
}

//将当前文件重命名到另一个文件
public boolean 重命名(java.io.File 新文件) {
return this.renameTo(新文件);
}





public boolean 删除() {
return this.delete();
}




public java.io.File[] 取子文件数组() {
return (java.io.File[])this.listFiles();
}

//将当前文件转换为资源标识符(URI)
public java.net.URI 到资源标识符() {
return (java.net.URI)this.toURI();
}

public long 最后修改时间() {
return this.lastModified();
}

public void 最后修改时间(long 时间戳) {
this.setLastModified(时间戳);
}

public boolean 可读() {
return this.canRead();
}

public void 可读(boolean 是否可读) {
this.setReadable(是否可读);
}

public boolean 可写() {
return this.canWrite();
}

public void 可写(boolean 是否可写) {
this.setWritable(是否可写);
}

public boolean 可执行() {
return this.canExecute();
}

public void 可执行(boolean 是否可执行) {
this.setExecutable(是否可执行);
}

public boolean 存在() {
return this.exists();
}

public boolean 是否为隐藏文件() {
return this.isHidden();
}
//大小（字节长度）
public long 长度() {
return this.length();
}

}