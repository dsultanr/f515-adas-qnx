# Убирает Split, которые DSP SNPE 1.55 не принимает на вход Conv: веса предыдущей Conv режутся на части.
#  - C2f: Conv -> SiLU(Mul(x,Sigmoid x)) -> Split(axis=1)  =>  по Conv+SiLU на каждую часть;
#  - внимание: Conv -> Reshape[1,heads,ch,HW] -> Split(axis=2) q/k/v  =>  по Conv+Reshape на q, k, v.
import onnx,sys,numpy as np
from onnx import helper, numpy_helper, shape_inference
m=onnx.load(sys.argv[1]); g=m.graph
init={i.name:i for i in g.initializer}
arr=lambda n: numpy_helper.to_array(init[n])
prod={o:n for n in g.node for o in n.output}
cons={}
for n in g.node:
    for i in n.input: cons.setdefault(i,[]).append(n)
new_nodes={}; drop=set()
def add_init(name,a): g.initializer.append(numpy_helper.from_array(a.astype(np.float32) if a.dtype!=np.int64 else a,name))
def conv_part(conv,idx,tag):
    w=arr(conv.input[1]); b=arr(conv.input[2]) if len(conv.input)>2 else None
    wn=f'{conv.input[1]}_{tag}'; add_init(wn,w[idx]); ins=[conv.input[0],wn]
    if b is not None: bn=f'{conv.input[2]}_{tag}'; add_init(bn,b[idx]); ins.append(bn)
    return helper.make_node('Conv',ins,[f'{conv.output[0]}_{tag}'],name=f'{conv.name}_{tag}',
        **{a.name:helper.get_attribute_value(a) for a in conv.attribute})
for n in list(g.node):
    if n.op_type!='Split': continue
    ax={a.name:helper.get_attribute_value(a) for a in n.attribute}
    sizes=ax['split']; p=prod[n.input[0]]
    if ax['axis']==1 and p.op_type=='Mul':
        sig=prod[p.input[1]]; conv=prod[p.input[0]]
        assert sig.op_type=='Sigmoid' and conv.op_type=='Conv' and sig.input[0]==conv.output[0]
        assert len(cons[conv.output[0]])==2 and len(cons[p.output[0]])==1
        out=[]; off=0
        for k,(s,o) in enumerate(zip(sizes,n.output)):
            c=conv_part(conv,np.arange(off,off+s),f'p{k}'); off+=s
            sg=helper.make_node('Sigmoid',[c.output[0]],[c.output[0]+'_sig'],name=c.name+'_sig')
            ml=helper.make_node('Mul',[c.output[0],sg.output[0]],[o],name=c.name+'_silu')
            out+=[c,sg,ml]
        new_nodes[n.name]=out; drop|={conv.name,sig.name,p.name,n.name}
        print('C2f split fixed',n.name)
    elif ax['axis']==2 and p.op_type=='Reshape':
        conv=prod[p.input[0]]; assert conv.op_type=='Conv' and len(cons[conv.output[0]])==1
        shape=arr(p.input[1]).copy(); heads,ch=shape[1],shape[2]; assert sum(sizes)==ch
        out=[]; off=0
        for k,(s,o) in enumerate(zip(sizes,n.output)):
            idx=np.concatenate([h*ch+np.arange(off,off+s) for h in range(heads)]); off+=s
            c=conv_part(conv,idx,f'qkv{k}')
            sh=shape.copy(); sh[2]=s; sn=f'{p.input[1]}_qkv{k}'; add_init(sn,sh.astype(np.int64))
            out+=[c,helper.make_node('Reshape',[c.output[0],sn],[o],name=f'{p.name}_qkv{k}')]
        new_nodes[n.name]=out; drop|={conv.name,p.name,n.name}
        print('attention split fixed',n.name)
    else:
        print('SKIP',n.name,ax,p.op_type)
nodes=[]
for n in g.node:
    if n.name in new_nodes: nodes+=new_nodes[n.name]
    elif n.name not in drop: nodes.append(n)
# топологический порядок: новые узлы встали на место Split, их входы (вход старой Conv) уже определены выше
del g.node[:]; g.node.extend(nodes); del g.value_info[:]
m.ir_version=min(m.ir_version,7); m=shape_inference.infer_shapes(m); onnx.checker.check_model(m); onnx.save(m,sys.argv[2])
