### pass 1 
创建scope,scope 挂在对应的AST节点上   
在scope中加入所有item,item内容暂时不填充
### pass2
对于所有的 function,trait、impl中的函数，进入其中填充返回类型，参数类型  
填充所有的struct和enum，可能需要递归完成  
常量解析 直接递归对所有常量求值

### pass3
将impl挂到对应item上

### pass4
定义所有变量  
进行控制流检查

