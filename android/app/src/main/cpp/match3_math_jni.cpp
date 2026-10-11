#include <jni.h>
#include <algorithm>
#include <cmath>
#include <limits>

namespace {
constexpr float maximum=.12f, margin=.025f;
constexpr float infinity=std::numeric_limits<float>::infinity();
constexpr jlong recordSize=7352;
void prefix(const jint* pixels,jint* sums) {
    std::fill(sums,sums+867,0);
    for(int y=0;y<16;y++)for(int x=0;x<16;x++)for(int k=0;k<3;k++) {
        int at=k*289+(y+1)*17+x+1;
        sums[at]=(pixels[y*16+x]>>(16-8*k)&255)+sums[at-1]+sums[at-17]-sums[at-18];
    }
}
int sum(const jint* s,int k,int l,int t,int r,int b) {
    s+=k*289;return s[b*17+r]-s[t*17+r]-s[b*17+l]+s[t*17+l];
}
bool outsideBudget(const jint* reference,const jint* observed,int dx,int dy,double budget) {
    int l=std::max(0,-dx),t=std::max(0,-dy),r=std::min(16,16-dx),b=std::min(16,16-dy),lower=0;
    for(int k=0;k<3;k++)lower+=std::abs(sum(reference,k,l,t,r,b)-sum(observed,k,l+dx,t+dy,r+dx,b+dy));
    if(lower>budget)return true;
    lower=0;
    for(int y=0;y<16;y+=4)for(int x=0;x<16;x+=4) {
        l=std::max(x,-dx);t=std::max(y,-dy);r=std::min(x+4,16-dx);b=std::min(y+4,16-dy);
        if(l>=r || t>=b)continue;
        for(int k=0;k<3;k++)lower+=std::abs(sum(reference,k,l,t,r,b)-sum(observed,k,l+dx,t+dy,r+dx,b+dy));
        if(lower>budget)return true;
    }
    return false;
}
bool pixels(JNIEnv* env,jintArray input,jint* output) {
    if(!input || env->GetArrayLength(input)!=256)return false;
    env->GetIntArrayRegion(input,0,256,output);return !env->ExceptionCheck();
}
int error(jint a,jint b) {
    return std::abs((a>>16&255)-(b>>16&255))+std::abs((a>>8&255)-(b>>8&255))+std::abs((a&255)-(b&255));
}
bool backdrop(jint p) {
    int r=p>>16&255,g=p>>8&255,b=p&255,high=std::max(r,std::max(g,b)),low=std::min(r,std::min(g,b));
    if(high>=128 || high==low)return false;
    float delta=static_cast<float>(high-low);
    float hue=high==r?60*std::fmod((g-b)/delta,6.0f):high==g?60*((b-r)/delta+2):60*((r-g)/delta+4);
    if(hue<0)hue+=360;
    return hue>=170 && hue<=300;
}
float bodyAligned(const jint* observed,const jint* reference,const jboolean* background,int dx,int dy) {
    for(int y=0;y<16;y++)for(int x=0;x<16;x++)
        if((x-dx<0 || x-dx>=16 || y-dy<0 || y-dy>=16) && !background[y*16+x])return infinity;
    int supported=(16-std::abs(dx))*(16-std::abs(dy)),total=0,quarters[4]={},counts[4]={};
    double budget=static_cast<double>(std::nextafter(maximum+margin,infinity))*supported*765;
    for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
        int xx=x+dx,yy=y+dy;if(xx<0 || xx>=16 || yy<0 || yy>=16)continue;
        int delta=error(observed[yy*16+xx],reference[y*16+x]),quarter=(y/8)*2+x/8;
        total+=delta;quarters[quarter]+=delta;counts[quarter]++;
        if(total>budget)return infinity;
    }
    for(int i=0;i<4;i++)if(quarters[i]/(counts[i]*765.0f)>maximum+margin)return infinity;
    return total/(supported*765.0f);
}
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_bodyDifference(JNIEnv* env,jclass,jintArray input,jintArray anchor,jbooleanArray backdropMask) {
    jint observed[256],reference[256];jboolean background[256];
    if(!pixels(env,input,observed) || !pixels(env,anchor,reference))return infinity;
    if(backdropMask) {
        if(env->GetArrayLength(backdropMask)!=256)return infinity;
        env->GetBooleanArrayRegion(backdropMask,0,256,background);if(env->ExceptionCheck())return infinity;
    } else for(int i=0;i<256;i++)background[i]=backdrop(observed[i]);
    float best=bodyAligned(observed,reference,background,0,0);
    // Preserve the Java descriptor's exact zero-offset acceptance behavior.
    if(best<=maximum)return best;
    for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if(dx!=0 || dy!=0)
        best=std::min(best,bodyAligned(observed,reference,background,dx,dy));
    return best;
}

namespace {
// Each offset's valid pixels are ordered with the positive cover evidence first.
// Rejecting that independent constraint needs no work on the animal underneath.
void coverOrder(const jboolean* cover,jint* supports,jboolean* indices) {
    int offset=0;
    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++,offset++) {
        int size=0;auto* order=indices+offset*256;
        for(int pass=1;pass>=0;pass--) {
            for(int y=std::max(0,-dy);y<std::min(16,16-dy);y++)for(int x=std::max(0,-dx);x<std::min(16,16-dx);x++)
                if((cover[y*16+x]!=0)==(pass!=0))order[size++]=static_cast<jboolean>(y*16+x);
            if(pass==1)supports[offset]=size;
        }
    }
}
float coverScore(const jint* reference,const jint* observed,float ceiling,const jint* referenceSums,const jint* observedSums,const jint* supports,const jboolean* indices) {
    float best=infinity;
    int offset=0;
    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++,offset++) {
        int support=supports[offset],count=(16-std::abs(dx))*(16-std::abs(dy));
        if(support<24)continue;
        double wholeBudget=static_cast<double>(std::nextafter(ceiling,infinity))*count*765;
        double coverBudget=static_cast<double>(std::nextafter(ceiling,infinity))*support*765;
        auto* order=indices+offset*256;int shift=dy*16+dx;
        int partial=0;bool rejected=false;
        for(int i=0;i<support;i++) {
            int at=order[i];partial+=error(reference[at],observed[at+shift]);
            if(partial>coverBudget) { rejected=true;break; }
        }
        if(rejected || outsideBudget(referenceSums,observedSums,dx,dy,wholeBudget))continue;
        int whole=partial;
        for(int i=support;i<count;i++) {
            int at=order[i];whole+=error(reference[at],observed[at+shift]);
            if(whole>wholeBudget) { rejected=true;break; }
        }
        if(!rejected) {
            float score=std::max(whole/(count*765.0f),partial/(support*765.0f));
            if(score<=ceiling)best=std::min(best,score);
        }
    }
    return best;
}
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_coverDifference(JNIEnv* env,jclass,jintArray anchor,jintArray input,jbooleanArray stencil,jfloat ceiling) {
    jint observed[256],reference[256];jboolean cover[256];
    if(!pixels(env,input,observed) || !pixels(env,anchor,reference) || !stencil || env->GetArrayLength(stencil)!=256)return infinity;
    env->GetBooleanArrayRegion(stencil,0,256,cover);if(env->ExceptionCheck())return infinity;
    jint referenceSums[867],observedSums[867];prefix(reference,referenceSums);prefix(observed,observedSums);
    jint supports[9];jboolean indices[9*256];coverOrder(cover,supports,indices);
    return coverScore(reference,observed,ceiling,referenceSums,observedSums,supports,indices);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_preparePatternTable(JNIEnv* env,jclass,jobject table,jint count) {
    if(!table || count<1 || env->GetDirectBufferCapacity(table)!=recordSize*count)return false;
    auto* bytes=static_cast<jboolean*>(env->GetDirectBufferAddress(table));if(!bytes)return false;
    for(int i=0;i<count;i++) {
        auto* at=bytes+recordSize*i;auto* header=reinterpret_cast<jint*>(at);
        if(header[1]==256)prefix(header+2,reinterpret_cast<jint*>(at+1544));
        if(header[0]==1 && header[1]==256)coverOrder(at+1288,reinterpret_cast<jint*>(at+5012),at+5048);
    }
    return true;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_patternDifferences(JNIEnv* env,jclass,jintArray input,jobject table,jint count,jfloat ceiling) {
    jint observed[256];
    if(!pixels(env,input,observed) || !table || count<1 || env->GetDirectBufferCapacity(table)!=recordSize*count)return nullptr;
    auto* bytes=static_cast<const jboolean*>(env->GetDirectBufferAddress(table));if(!bytes)return nullptr;
    jfloatArray output=env->NewFloatArray(count);if(!output)return nullptr;
    jint observedSums[867];prefix(observed,observedSums);
    // Small chunks keep stack usage fixed even for the largest allowed gallery.
    float scores[64];
    for(int offset=0;offset<count;offset+=64) {
        int size=std::min(64,count-offset);
        for(int i=0;i<size;i++) {
            auto* at=bytes+recordSize*(offset+i);auto* header=reinterpret_cast<const jint*>(at);
            auto* reference=header+2;auto* mask=at+8+1024;
            auto* referenceSums=reinterpret_cast<const jint*>(at+1544);
            if(header[0]==1 && header[1]==256)scores[i]=coverScore(reference,observed,ceiling,referenceSums,observedSums,reinterpret_cast<const jint*>(at+5012),at+5048);
            else if(header[0]==0 && header[1]>=24 && header[1]<=256) {
                int total=0;double budget=static_cast<double>(std::nextafter(ceiling,infinity))*header[1]*765;
                if(header[1]==256 && outsideBudget(referenceSums,observedSums,0,0,budget)) {scores[i]=infinity;continue;}
                for(int p=0;p<256;p++)if(mask[p]) { total+=error(reference[p],observed[p]);if(total>budget)break; }
                scores[i]=total>budget?infinity:total/(header[1]*765.0f);
            } else scores[i]=infinity;
        }
        env->SetFloatArrayRegion(output,offset,size,scores);if(env->ExceptionCheck())return nullptr;
    }
    return output;
}
