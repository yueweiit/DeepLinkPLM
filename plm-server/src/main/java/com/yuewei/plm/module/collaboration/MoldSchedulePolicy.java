package com.yuewei.plm.module.collaboration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.yuewei.plm.module.collaboration.CollaborationFields.*;
import static com.yuewei.plm.module.collaboration.MoldScheduleTypes.*;

/** One saved-state policy for task entry, ledger badges, reminders and SQL filtering. */
public final class MoldSchedulePolicy {
    private MoldSchedulePolicy() {}
    private record Completion(Map<String,String> values,List<String> fields,Map<String,String> equalFields) {}
    private record TaskRule(TaskDefinition definition,List<String> activationFields,Completion completion) {}
    private static Completion present(String... fields) {return new Completion(Map.of(),List.of(fields),Map.of());}
    private static TaskRule rule(String key,String title,String category,String group,String due,String owner,List<String> input,List<String> support,List<String> active,Completion completion) {
        return new TaskRule(new TaskDefinition(key,title,category,group,owner,due,input,support),active,completion);
    }
    private static final List<TaskRule> TASK_RULES=List.of(
        rule("mold_complete","确认模具制作完成","milestone","engineering","plannedMoldDeliveryDate","ownerUserId",
            List.of("pcStatus","tpuStatus"),List.of("actualSupplierTrialDate","notes"),List.of(),
            new Completion(Map.of("pcStatus","ready","tpuStatus","ready"),List.of(),Map.of())),
        rule("ship","填写实际发模","milestone","logistics","plannedMoldDeliveryDate","logisticsOwnerUserId",
            List.of("actualShipDate"),List.of("pcCurrentLocation","tpuCurrentLocation","actualPortArrivalDate"),List.of(),present("actualShipDate")),
        rule("factory_arrival","确认 PC / TPU 实际到厂","milestone","mexico","plannedFactoryArrivalDate","mexicoOwnerUserId",
            List.of("pcFactoryArrivalDate","tpuFactoryArrivalDate"),List.of("pcSignedDate","tpuSignedDate","pcCurrentLocation","tpuCurrentLocation"),List.of(),present("pcFactoryArrivalDate","tpuFactoryArrivalDate")),
        rule("acceptance","完成 PC / TPU 验收","milestone","mexico","plannedAcceptanceDate","mexicoOwnerUserId",
            List.of("pcAcceptanceStatus","tpuAcceptanceStatus","pcAcceptanceDate","tpuAcceptanceDate","pcAcceptanceIssues","tpuAcceptanceIssues"),List.of("pcFactoryArrivalDate","tpuFactoryArrivalDate"),List.of(),
            new Completion(Map.of("pcAcceptanceStatus","passed","tpuAcceptanceStatus","passed"),List.of("pcFactoryArrivalDate","tpuFactoryArrivalDate","pcAcceptanceDate","tpuAcceptanceDate"),Map.of())),
        rule("production","填写试模量产","milestone","mexico","plannedProductionDate","productionOwnerUserId",
            List.of("actualProductionDate"),List.of("actualTrialDate"),List.of(),present("actualProductionDate")),
        rule("opening_plan","补填开模与模具交付计划","missing","engineering",null,"ownerUserId",
            List.of("openedDate","plannedMoldDeliveryDate"),List.of("manufacturerName","ownerUserId","planStartDate","manufacturingDays","holidayDays","extraDays","notes"),List.of(),present("openedDate","plannedMoldDeliveryDate")),
        rule("factory_plan","补填计划到墨西哥工厂日期","missing","logistics",null,"logisticsOwnerUserId",
            List.of("plannedFactoryArrivalDate"),List.of("logisticsOwnerUserId","forecastFactoryDate"),List.of(),present("plannedFactoryArrivalDate")),
        rule("acceptance_plan","补填计划验收日期","missing","mexico",null,"mexicoOwnerUserId",
            List.of("plannedAcceptanceDate"),List.of("mexicoOwnerUserId","forecastAcceptanceDate"),List.of(),present("plannedAcceptanceDate")),
        rule("production_plan","补填计划试模量产日期","missing","mexico",null,"productionOwnerUserId",
            List.of("plannedProductionDate"),List.of("productionOwnerUserId","forecastProductionDate"),List.of(),present("plannedProductionDate")),
        rule("arrival_review","重新核对到厂计划","review","logistics",null,"logisticsOwnerUserId",
            List.of("plannedFactoryArrivalDate"),List.of("plannedMoldDeliveryDate","arrivalConfirmedBasisDate"),List.of("plannedFactoryArrivalDate"),
            new Completion(Map.of(),List.of(),Map.of("arrivalConfirmedBasisDate","plannedMoldDeliveryDate"))),
        rule("mexico_arrival","发模后补填实际抵墨日期","missing","logistics",null,"logisticsOwnerUserId",
            List.of("actualMexicoArrivalDate"),List.of("actualShipDate","actualPortArrivalDate","pcCurrentLocation","tpuCurrentLocation"),List.of("actualShipDate"),present("actualMexicoArrivalDate")));
    private static final Map<String,TaskDefinition> DEFINITIONS=new LinkedHashMap<>();
    private static final Map<String,String> READ_ONLY_COLUMNS=Map.of("arrivalConfirmedBasisDate","s.arrival_confirmed_basis_date");
    static {TASK_RULES.forEach(rule->DEFINITIONS.put(rule.definition.taskKey(),rule.definition));}

    public static LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    public static List<TaskDefinition> definitions() {return TASK_RULES.stream().map(TaskRule::definition).toList();}
    static TaskDefinition definition(String key) {return Objects.requireNonNull(DEFINITIONS.get(key),"Unknown code-owned task key");}
    static String category(String category) {
        String value=category==null?"milestone":category;
        if(!Set.of("milestone","missing","review","all").contains(value)) throw invalid("待办类别无效");
        return value;
    }
    public static List<Task> tasksFor(Map<String,Object> row,LocalDate today) {return tasksFor(row,today,"milestone");}
    public static List<Task> tasksFor(Map<String,Object> row,LocalDate today,String category) {
        String selection=category(category);
        if(Boolean.TRUE.equals(row.get("schedulePaused"))) return List.of();
        return TASK_RULES.stream().filter(rule->selection.equals("all")||rule.definition.category().equals(selection))
            .filter(rule->hasFields(row,rule.activationFields)&&!complete(row,rule.completion)).map(rule->{
                var d=rule.definition;LocalDate due=d.dueField()==null?null:date(row.get(d.dueField()));Object owner=row.get(d.ownerField());
                boolean overdue=due!=null&&due.isBefore(today);Object item=row.get("itemId");
                return new Task(((Number)row.get("moldSetId")).longValue(),Objects.toString(row.get("assetNumber")),Objects.toString(row.get("productLineName"),Objects.toString(row.get("styleName"))),Objects.toString(row.get("phoneModelCodeName"),Objects.toString(row.get("compatibleModels"))),d.taskKey(),d.title(),d.group(),owner==null?null:((Number)owner).longValue(),due==null?null:due.toString(),overdue,d.dueField(),overdue?ChronoUnit.DAYS.between(due,today):0,item==null?null:((Number)item).longValue(),d.category(),d.inputFields(),d.supportFields());
            }).toList();
    }
    public static void enrichTasks(Map<String,Object> row,LocalDate today) {
        List<Task> tasks=tasksFor(row,today);
        row.put("tasks",tasks);row.put("overdueTasks",tasks.stream().filter(Task::overdue).toList());
    }
    static String overdueSql() {
        return MoldWorkflowRepository.activeSql("s")+" and ("+String.join(" or ",TASK_RULES.stream().filter(rule->rule.definition.dueField()!=null)
            .map(rule->"("+column(rule.definition.dueField())+" < (now() at time zone 'Asia/Shanghai')::date and ("+presentSql(rule.activationFields)+") and not coalesce(("+completeSql(rule.completion)+"),false))").toList())+")";
    }
    static String taskValuesSql() {
        return String.join(",",TASK_RULES.stream().map(rule->{var d=rule.definition;return "('"+d.taskKey()+"','"+d.title()+"','"+d.group()+"','"+d.category()+"',"+(d.dueField()==null?"null::text":"'"+d.dueField()+"'")+","+(d.dueField()==null?"null::date":column(d.dueField()))+","+column(d.ownerField())+",("+presentSql(rule.activationFields)+"),("+completeSql(rule.completion)+"))";}).toList());
    }
    private static boolean hasFields(Map<String,Object> row,List<String> fields) {return fields.stream().allMatch(key->row.get(key)!=null);}
    private static boolean complete(Map<String,Object> row,Completion condition) {
        return condition.values.entrySet().stream().allMatch(e->e.getValue().equals(row.get(e.getKey())))&&hasFields(row,condition.fields)&&condition.equalFields.entrySet().stream().allMatch(e->same(row.get(e.getKey()),row.get(e.getValue())));
    }
    private static String column(String key) {
        if(READ_ONLY_COLUMNS.containsKey(key)) return READ_ONLY_COLUMNS.get(key);
        Field f=field("mold_set",key);return (partField(f)?key.startsWith("pc")?"pc.":"tpu.":"s.")+f.column();
    }
    private static String presentSql(List<String> fields) {return fields.isEmpty()?"true":String.join(" and ",fields.stream().map(key->column(key)+" is not null").toList());}
    private static String completeSql(Completion condition) {
        List<String> clauses=new ArrayList<>();condition.values.forEach((key,value)->clauses.add(column(key)+"='"+value+"'"));
        condition.fields.forEach(key->clauses.add(column(key)+" is not null"));condition.equalFields.forEach((key,other)->clauses.add(column(key)+" is not distinct from "+column(other)));
        return clauses.isEmpty()?"true":String.join(" and ",clauses);
    }
}
