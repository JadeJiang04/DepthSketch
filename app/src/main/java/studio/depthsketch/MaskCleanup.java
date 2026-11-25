package studio.depthsketch;

import java.util.Arrays;
import java.util.List;

/** Conservative eight-connected island removal. Does not erode the subject's boundary. */
final class MaskCleanup {
    static final class Result {
        int components,removedComponents,removedPixels,foreground;
    }
    static Result clean(float[] logits,int w,int h,List<float[]> prompts) {
        if(w<1||h<1||(long)w*h!=logits.length)throw new IllegalArgumentException("分割尺寸无效");
        int[] labels=new int[logits.length],queue=new int[logits.length],sizes=new int[128];
        Result result=new Result();int largest=0;
        for(int p=0;p<logits.length;p++){
            if(!Float.isFinite(logits[p]))throw new IllegalArgumentException("无效的分割结果");
            if(logits[p]<=0||labels[p]!=0)continue;
            int id=++result.components;if(id>=sizes.length)sizes=Arrays.copyOf(sizes,sizes.length*2);
            int head=0,tail=1;queue[0]=p;labels[p]=id;
            while(head<tail){
                int q=queue[head++],x=q%w,y=q/w;
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){
                    int n=yy*w+xx;if(labels[n]==0&&logits[n]>0){labels[n]=id;queue[tail++]=n;}
                }
            }
            sizes[id]=tail;largest=Math.max(largest,tail);result.foreground+=tail;
        }
        // Small only: at most 256 pixels and 0.05% of the main part (8 px floor).
        // This also catches the narrow remote 95 px stripe in the supplied second character.
        int threshold=Math.max(8,Math.min(256,largest/2000));boolean[] keep=new boolean[result.components+1];
        for(int id=1;id<keep.length;id++)keep[id]=sizes[id]>threshold||sizes[id]==largest;
        if(prompts!=null)for(float[] prompt:prompts)if(prompt[2]>0){
            int x=Math.max(0,Math.min(w-1,Math.round(prompt[0]))),y=Math.max(0,Math.min(h-1,Math.round(prompt[1])));
            // Preserve a user-selected small part even if the click falls on its antialiased edge.
            for(int yy=Math.max(0,y-2);yy<=Math.min(h-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(w-1,x+2);xx++){
                int id=labels[yy*w+xx];if(id!=0)keep[id]=true;
            }
        }
        boolean[] anchor=keep.clone();
        // Detached strands immediately next to a retained part are not remote speckles.
        for(int p=0;p<labels.length;p++){
            int id=labels[p];if(id==0||keep[id])continue;int x=p%w,y=p/w;
            nearby:for(int yy=Math.max(0,y-3);yy<=Math.min(h-1,y+3);yy++)for(int xx=Math.max(0,x-3);xx<=Math.min(w-1,x+3);xx++){
                int other=labels[yy*w+xx];if(other!=0&&anchor[other]){keep[id]=true;break nearby;}
            }
        }
        for(int id=1;id<keep.length;id++)if(!keep[id]){result.removedComponents++;result.removedPixels+=sizes[id];}
        result.foreground-=result.removedPixels;
        for(int p=0;p<logits.length;p++){
            int id=labels[p];if(id!=0){if(!keep[id])logits[p]=-32;continue;}
            // Only retain the soft alpha fringe next to kept foreground. Otherwise faint islands
            // below the binary threshold would survive the cleanup as visible alpha speckles.
            if(logits[p]<=-.5f)continue;boolean supported=false;int x=p%w,y=p/w;
            nearby:for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){
                int other=labels[yy*w+xx];if(other!=0&&keep[other]){supported=true;break nearby;}
            }
            if(!supported)logits[p]=-32;
        }
        return result;
    }
}
