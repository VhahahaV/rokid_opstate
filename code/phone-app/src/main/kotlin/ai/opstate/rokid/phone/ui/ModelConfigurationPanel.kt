package ai.opstate.rokid.phone.ui

import ai.opstate.rokid.phone.runtime.DirectModelConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@Composable internal fun ModelConfigurationPanel(state: OperatorViewState,actions: OperatorActions) {
    var model by remember(state.directModel) {mutableStateOf(state.directModel.modelId)}
    var endpoint by remember(state.directModel) {mutableStateOf(state.directModel.endpoint)}
    var api by remember(state.directModel) {mutableStateOf(state.directModel.wireApi)}
    var maximum by remember(state.directModel) {mutableStateOf(state.directModel.maxOutputTokens.toString())}
    var temperature by remember(state.directModel) {mutableStateOf(state.directModel.temperature.toString())}
    var topP by remember(state.directModel) {mutableStateOf(state.directModel.topP.toString())}
    var seed by remember(state.directModel) {mutableStateOf(state.directModel.seed?.toString().orEmpty())}
    var thinking by remember(state.directModel) {mutableStateOf(state.directModel.enableThinking)}
    var reasoning by remember(state.directModel) {mutableStateOf(state.directModel.reasoningEffort.orEmpty())}
    var key by remember {mutableStateOf("")}
    var advanced by remember {mutableStateOf(false)}
    val editable=!state.busy && !state.replay.running && state.runtime.sessionId==null && state.runtime.phase!="PREPARING"
    fun preset(value: DirectModelConfig) {
        model=value.modelId;endpoint=value.endpoint;api=value.wireApi;maximum=value.maxOutputTokens.toString()
        temperature=value.temperature.toString();topP=value.topP.toString();seed=value.seed?.toString().orEmpty()
        thinking=value.enableThinking;reasoning=value.reasoningEffort.orEmpty();key=""
    }
    val draft=state.directModel.copy(modelId=model.trim(),endpoint=endpoint.trim(),wireApi=api,maxOutputTokens=maximum.toIntOrNull() ?: 0,
        temperature=temperature.toDoubleOrNull() ?: Double.NaN,topP=topP.toDoubleOrNull() ?: Double.NaN,
        seed=seed.toLongOrNull(),enableThinking=thinking,reasoningEffort=reasoning.trim().takeIf {it.isNotEmpty()})
    val valid=runCatching {draft.validate();require(seed.isBlank() || seed.toLongOrNull()!=null)}.isSuccess
    val sameScope=runCatching {draft.credentialScope()==state.directModel.credentialScope()}.getOrDefault(false)
    val savedKey=state.modelKeyPresent && sameScope
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Panel {
            SectionTitle("模型配置","DIRECT MODEL API")
            Text("手机通过 API Key 直接调用模型。",style=MaterialTheme.typography.bodyMedium,color=Quiet)
            // A choice, not a text field: the model has to match a prompt and a parser that were frozen
            // for it, so a hand-typed id could only ever fail at request time.
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DirectModelConfig.choices().forEach {(label,value)->
                    FilterChip(selected=model==value.modelId,onClick={preset(value)},enabled=editable,label={Text(label)})
                }
            }
            Text(model,style=MaterialTheme.typography.bodySmall,color=Quiet)
            OutlinedTextField(endpoint,{endpoint=it},label={Text("API 地址")},supportingText={Text("可填写 Base URL 或完整接口地址")},enabled=editable,singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=api=="chat_completions",onClick={api="chat_completions"},enabled=editable,label={Text("Chat Completions")})
                FilterChip(selected=api=="responses",onClick={api="responses"},enabled=editable,label={Text("Responses")})
            }
            OutlinedTextField(key,{key=it},label={Text(if(savedKey) "API Key · 留空保留已保存值" else "API Key")},enabled=editable,singleLine=true,
                visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
            Text(if(savedKey) "已加密保存当前接口的 Key。" else "更换接口地址后需填写对应的 Key。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "收起高级参数" else "高级参数")}
            if(advanced) {
                OutlinedTextField(maximum,{maximum=it},label={Text("最大输出 token")},enabled=editable,singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
                if(api=="chat_completions") {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(temperature,{temperature=it},label={Text("Temperature")},enabled=editable,singleLine=true,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp))
                        OutlinedTextField(topP,{topP=it},label={Text("Top P")},enabled=editable,singleLine=true,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp))
                    }
                    OutlinedTextField(seed,{seed=it},label={Text("Seed · 可留空")},enabled=editable,singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
                    Text("Thinking 参数",style=MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(null to "不发送",false to "关闭",true to "开启").forEach {(value,label)->
                            FilterChip(selected=thinking==value,onClick={thinking=value},enabled=editable,label={Text(label)})
                        }
                    }
                } else OutlinedTextField(reasoning,{reasoning=it},label={Text("推理级别 · 可留空")},supportingText={Text("如 low、medium、high")},enabled=editable,singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
            }
            PrimaryButton("保存模型与 Key",editable && valid && (key.isNotBlank() || savedKey)) {actions.saveModel(draft,key);key=""}
            OutlinedButton(onClick=actions.probe,enabled=editable && state.modelKeyPresent && draft==state.directModel,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)) {Text("验证已保存的 API Key")}
            Text("验证会发送一条生成图像与合成文字的测试请求。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            if(state.modelKeyPresent) TextButton(onClick=actions.clearModelKey,enabled=editable) {Text("移除已保存的模型 Key")}
        }
        Panel {
            SectionTitle("连接状态","MODEL STATUS")
            Tag(state.modelApiStatus,if(state.modelContract!=null) Success else Quiet)
            Text(state.directModel.modelId,style=MaterialTheme.typography.titleLarge)
            SettingRow("连接方式","手机直连 · API Key")
            SettingRow("接口",state.directModel.wireApi)
            state.directModel.parameters().forEach {(name,value)->SettingRow(when(name) {
                "max_output_tokens"->"最大输出";"reasoning_effort"->"推理级别";"enable_thinking"->"Thinking";"top_p"->"Top P";"temperature"->"Temperature";else->"Seed"
            },value.jsonPrimitive.content)}
            state.modelContract?.modelConfig?.get("config_sha256")?.jsonPrimitive?.content?.let {
                Text("配置快照  ${it.take(16)}…",style=MaterialTheme.typography.bodySmall,color=Quiet,fontFamily=FontFamily.Monospace)
            }
        }
    }
}
