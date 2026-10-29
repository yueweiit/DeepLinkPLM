package com.yuewei.plm.module.partner;

import java.io.*;
import java.nio.file.*;
import org.springframework.web.multipart.MultipartFile;

/** Disk-backed adapter: callers must stream; it deliberately never materialises a whole file. */
public final class PartnerPathFile implements MultipartFile {
 private final Path path; private final String name; private final String type;
 public PartnerPathFile(Path path,String name,String type){this.path=path;this.name=name;this.type=type;}
 public String getName(){return "file";} public String getOriginalFilename(){return name;}
 public String getContentType(){return type;} public boolean isEmpty(){return getSize()==0;}
 public long getSize(){try{return Files.size(path);}catch(IOException e){throw new UncheckedIOException(e);}}
 public byte[] getBytes(){throw new UnsupportedOperationException("大文件必须流式读取");}
 public InputStream getInputStream() throws IOException{return Files.newInputStream(path);}
 public void transferTo(File destination) throws IOException{Files.copy(path,destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
}
