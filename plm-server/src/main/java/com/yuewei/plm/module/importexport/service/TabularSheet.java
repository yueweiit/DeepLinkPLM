package com.yuewei.plm.module.importexport.service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;
import com.yuewei.plm.common.exception.BusinessException;
/** Shared CSV quoting and POI workbook I/O; domain matching stays in business services. */
public final class TabularSheet {
 private TabularSheet(){}
 private static String text(Object value){return value==null?"":value.toString().trim();}
 private static BusinessException invalid(String message){return new BusinessException(40001,message);}
 private static void require(boolean condition,String message){if(!condition)throw invalid(message);}
 public static List<List<String>> csv(String input){
  String value=input.replaceFirst("^\\uFEFF","");List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quote=false;
  for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"'){if(quote&&i+1<value.length()&&value.charAt(i+1)=='"'){cell.append('"');i++;}else{require(quote||cell.length()==0,"CSV 引号位置无效");quote=!quote;}}
   else if(c==','&&!quote){row.add(cell.toString());cell.setLength(0);}else if((c=='\n'||c=='\r')&&!quote){if(c=='\r'&&i+1<value.length()&&value.charAt(i+1)=='\n')i++;row.add(cell.toString());rows.add(row);row=new ArrayList<>();cell.setLength(0);}else cell.append(c);
  }require(!quote,"CSV 引号未闭合");if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());rows.add(row);}return rows;
 }
 public static List<Map<String,String>> read(MultipartFile file){
  require(file!=null&&!file.isEmpty()&&file.getSize()<=5*1024*1024,"请选择不超过5MB的CSV或XLSX");String name=text(file.getOriginalFilename()).toLowerCase(Locale.ROOT);List<List<String>> rows=new ArrayList<>();
  try{if(name.endsWith(".csv"))rows=csv(new String(file.getBytes(),StandardCharsets.UTF_8));
   else{require(name.endsWith(".xlsx"),"请使用CSV或XLSX模板");try(var input=file.getInputStream();var wb=WorkbookFactory.create(input)){require(wb.getNumberOfSheets()>0,"工作簿为空");var sheet=wb.getSheetAt(0);require(sheet.getLastRowNum()<=2000,"每次最多2000行");var fmt=new DataFormatter(Locale.ROOT);for(var r:sheet){List<String> cells=new ArrayList<>();require(r.getLastCellNum()<=100,"列数超过限制");for(int i=0;i<Math.max(0,r.getLastCellNum());i++){var c=r.getCell(i);require(c==null||c.getCellType()!=CellType.FORMULA,"请将公式粘贴为值后导入");cells.add(c==null?"":fmt.formatCellValue(c));}rows.add(cells);}}}
  }catch(com.yuewei.plm.common.exception.BusinessException e){throw e;}catch(Exception e){throw invalid("无法读取表格，请使用标准CSV或XLSX模板");}
  require(rows.size()>1&&rows.size()<=2001,"文件须有数据，最多2000行");List<String> headers=rows.get(0).stream().map(String::trim).toList();require(new HashSet<>(headers).size()==headers.size(),"表头不能重复");List<Map<String,String>> out=new ArrayList<>();
  for(int i=1;i<rows.size();i++){var r=rows.get(i);if(r.stream().allMatch(String::isBlank))continue;Map<String,String> row=new LinkedHashMap<>();for(int j=0;j<headers.size();j++)row.put(headers.get(j),j<r.size()?r.get(j).trim():"");row.put("_row",Integer.toString(i+1));out.add(row);}require(!out.isEmpty(),"文件没有数据行");return out;
 }
 public static byte[] workbook(String title,List<String> headers,List<Map<String,String>> rows){
  try(var wb=new XSSFWorkbook();var output=new ByteArrayOutputStream()){var sheet=wb.createSheet(title);var head=sheet.createRow(0);for(int i=0;i<headers.size();i++){head.createCell(i).setCellValue(headers.get(i));sheet.setColumnWidth(i,20*256);}int n=1;for(var row:rows){var r=sheet.createRow(n++);for(int i=0;i<headers.size();i++)r.createCell(i).setCellValue(row.getOrDefault(headers.get(i),""));}sheet.createFreezePane(0,1);wb.write(output);return output.toByteArray();}catch(IOException e){throw invalid("无法生成表格");}
 }
}
