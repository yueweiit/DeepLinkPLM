package com.yuewei.plm.module.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.module.masterdata.MasterDataSnapshotStore;
import com.yuewei.plm.module.process.dto.*;
import com.yuewei.plm.module.process.entity.ProcessEntity;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.masterdata.MasterDataSnapshot.*;

/** Graph relations extend, rather than replace, the existing Process route writer. */
@Component @RequiredArgsConstructor
public class ProcessGraphSupport {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final PilotAccessService access;private final OperationLogService audit;
    public static boolean graphNodes(List<ProcessOperationDTO> nodes){return nodes!=null&&nodes.stream().anyMatch(n->n.getNodeCode()!=null);}
    public static void validate(List<ProcessOperationDTO> nodes,List<ProcessRouteSaveDTO.Edge> edges){
        require(nodes!=null&&!nodes.isEmpty()&&edges!=null,"图路线必须完整提交节点及连接，旧客户端不能省略连接后覆盖");
        var names=new HashSet<String>();var sequences=new HashSet<Integer>();var degrees=new HashMap<String,Integer>();var next=new HashMap<String,List<String>>();
        for(var n:nodes){require(n.getNodeCode()!=null&&!n.getNodeCode().isBlank()&&n.getNodeCode().length()<=160&&names.add(n.getNodeCode()),"节点身份不能为空或重复");require(n.getSequenceNo()!=null&&n.getSequenceNo()>0&&sequences.add(n.getSequenceNo()),"节点显示顺序无效或重复");require(n.getOperationCode()!=null&&!n.getOperationCode().isBlank()&&n.getProcessName()!=null&&!n.getProcessName().isBlank(),"请明确节点的 MES 工序编码和名称");degrees.put(n.getNodeCode(),0);}
        var pairs=new HashSet<String>();for(var edge:edges){require(names.contains(edge.fromNodeCode())&&names.contains(edge.toNodeCode())&&!edge.fromNodeCode().equals(edge.toNodeCode()),"连接端点须是本路线内的不同节点");require(pairs.add(edge.fromNodeCode()+"\n"+edge.toNodeCode()),"连接重复");require(edge.relationType()!=null&&!edge.relationType().isBlank()&&edge.relationType().length()<=64,"请保留明确的连接类型");degrees.compute(edge.toNodeCode(),(k,v)->v+1);next.computeIfAbsent(edge.fromNodeCode(),k->new ArrayList<>()).add(edge.toNodeCode());}
        var ready=new ArrayDeque<String>();degrees.forEach((key,value)->{if(value==0)ready.add(key);});int count=0;while(!ready.isEmpty()){String key=ready.remove();count++;for(String n:next.getOrDefault(key,List.of()))if(degrees.compute(n,(k,v)->v-1)==0)ready.add(n);}require(count==nodes.size(),"工艺路线存在循环，不能保存");
    }
    public void before(ProcessRouteSaveDTO dto,Long id){
        if(!graphNodes(dto.getOperations())){
            if(id!=null)require(jdbc.queryForObject("select count(*) from plm_process where parent_process_id=? and node_code is not null and deleted_flag=0",Long.class,id)==0,"此路线包含图节点，不能由旧格式覆盖；请完整加载后维护");return;
        }
        access.requireEngineeringMaintenance();validate(dto.getOperations(),dto.getEdges());
        if(dto.getSourceProcessId()!=null){var source=jdbc.queryForMap("select product_id,process_param_json->>'sourceRouteCode' as source_route_code from plm_process where process_id=? and deleted_flag=0",dto.getSourceProcessId());access.requireProduct(((Number)source.get("product_id")).longValue());require(Objects.equals(dto.getSourceRouteCode(),source.get("source_route_code")),"复制路线的来源身份不一致，不能猜测");}
        for(var node:dto.getOperations())if(node.getOperationMasterProcessId()!=null)require(jdbc.queryForObject("select count(*) from plm_process where process_id=? and process_type='operation_master' and deleted_flag=0 and process_code=?",Long.class,node.getOperationMasterProcessId(),node.getOperationCode())==1,"节点所选工序与明确编码不一致");
        if(id!=null){jdbc.queryForList("select process_id from plm_process where process_id=? for update",id);require(Objects.equals(dto.getGraphOriginalHash(),fingerprint(id)),"路线在打开后已变化，请重新加载，不能覆盖");require(jdbc.queryForObject("select count(*) from plm_bom_item_target t join plm_product_bom_item i using(product_bom_item_id) join plm_product_bom b on b.product_bom_id=t.product_bom_id where t.route_process_id=? and i.deleted_flag=0 and b.deleted_flag=0 and b.status<>'archived'",Long.class,id)==0,"路线已有明确投料绑定，请复制新版本并在 BOM 中明确改用，不能改变旧节点身份");}
    }
    public ProcessEntity node(ProcessEntity route,ProcessOperationDTO dto,LocalDateTime now,String actor){
        var node=new ProcessEntity();node.setParentProcessId(route.getProcessId());node.setProductId(route.getProductId());node.setProcessType("operation");node.setProcessCode(route.getProcessCode()+"-N"+dto.getSequenceNo());node.setNodeCode(route.getProcessCode()+"-N"+dto.getSequenceNo());node.setLocalNodeCode(dto.getLocalNodeCode()==null?dto.getNodeCode():dto.getLocalNodeCode());node.setOperationMasterProcessId(dto.getOperationMasterProcessId());node.setProcessName(dto.getProcessName());node.setVersionNo(route.getVersionNo());node.setSequenceNo(dto.getSequenceNo());node.setPositionX(dto.getPositionX());node.setPositionY(dto.getPositionY());node.setStatus("draft");node.setQualityRequirement(dto.getQualityRequirement());node.setStandardTimeMins(dto.getStandardTimeMins());node.setRemark(dto.getRemark());
        try{var config=dto.getProcessParamJson()==null?json.createObjectNode():json.readTree(dto.getProcessParamJson());require(config.isObject(),"节点配置须为原始 JSON 对象");((ObjectNode)config).put("operationCode",dto.getOperationCode());node.setProcessParamJson(config.toString());}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw invalid("节点配置 JSON 无效");}
        node.setDeletedFlag(0);node.setCreatedAt(now);node.setUpdatedAt(now);node.setCreatedBy(actor);node.setUpdatedBy(actor);return node;
    }
    public void edges(ProcessEntity route,ProcessRouteSaveDTO dto){
        if(!graphNodes(dto.getOperations()))return;
        var ids=new HashMap<Integer,Long>();for(var row:jdbc.queryForList("select process_id,sequence_no from plm_process where parent_process_id=? and deleted_flag=0",route.getProcessId()))ids.put(((Number)row.get("sequence_no")).intValue(),((Number)row.get("process_id")).longValue());
        var byCode=new HashMap<String,Long>();dto.getOperations().forEach(n->byCode.put(n.getNodeCode(),ids.get(n.getSequenceNo())));
        jdbc.update("delete from plm_process_edge where route_id=?",route.getProcessId());
        int sequence=0;for(var edge:dto.getEdges())jdbc.update("insert into plm_process_edge(route_id,from_process_id,to_process_id,edge_no,relation_type,label,condition_json) values(?,?,?,?,?,?,?::jsonb)",route.getProcessId(),byCode.get(edge.fromNodeCode()),byCode.get(edge.toNodeCode()),edge.edgeNo()==null?++sequence:edge.edgeNo(),edge.relationType(),edge.label(),edge.condition()==null?null:edge.condition().toString());
        audit.logRequired(OperationLogCreateCommand.builder().action("PROCESS_GRAPH_SAVE").businessType("PROCESS").businessId(route.getProcessId().toString()).detailJson("{\"fingerprint\":\""+fingerprint(route.getProcessId())+"\",\"reason\":\"工程维护明确节点及依赖，不产生生产执行数据\"}").build(),access.actor());
    }
    public String fingerprint(long route){var parts=new ArrayList<String>();parts.add(jdbc.queryForObject("select row_to_json(p)::text from plm_process p where process_id=?",String.class,route));parts.addAll(jdbc.queryForList("select row_to_json(p)::text from plm_process p where parent_process_id=? and deleted_flag=0 order by process_id",String.class,route));parts.addAll(jdbc.queryForList("select row_to_json(e)::text from plm_process_edge e where route_id=? order by edge_id",String.class,route));return MasterDataSnapshotStore.sha256(String.join("\n",parts).getBytes(StandardCharsets.UTF_8));}
}
